package com.hermes.agent.data.plugin

import com.hermes.agent.data.local.dao.ScriptPluginDao
import com.hermes.agent.data.local.entity.ScriptPluginEntity
import com.hermes.agent.data.plugin.script.ScriptPluginEngine
import com.hermes.agent.data.plugin.script.ScriptPluginHost
import com.hermes.agent.data.plugin.script.ScriptPluginManifest
import com.hermes.agent.data.plugin.script.ScriptPluginRegistry
import com.hermes.agent.data.plugin.script.ScriptPluginRegistryEntry
import com.hermes.agent.data.plugin.script.ScriptPluginTool
import com.hermes.agent.domain.tool.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs, enables, and loads script modules.
 *
 * Owns the whole lifecycle: fetch the public registry, fetch and validate one
 * manifest, persist it with the permissions the user approved, then hand the
 * enabled set to [ScriptPluginEngine] and register the resulting tools so the
 * agent can call them.
 */
@Singleton
class ScriptPluginRepository @Inject constructor(
    private val dao: ScriptPluginDao,
    private val engine: ScriptPluginEngine,
    private val toolRegistry: ToolRegistry,
    host: ScriptPluginHost,
) {

    init {
        // The engine stays host-agnostic and unit-testable without this being
        // wired; here is where the real, Room/OkHttp-backed implementation is
        // handed to it for actual installs.
        engine.host = host
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Tool names this repository has registered, so reloads can unregister cleanly. */
    private val registeredToolNames = mutableSetOf<String>()

    fun observeInstalled(): Flow<List<ScriptPluginEntity>> = dao.observeAll()

    suspend fun fetchRegistry(url: String = DEFAULT_REGISTRY_URL): Result<List<ScriptPluginRegistryEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = httpGet(url)
                ScriptPluginManifest.json.decodeFromString<ScriptPluginRegistry>(body).plugins
            }
        }

    /** Fetches and validates a manifest without installing it, for the approval prompt. */
    suspend fun fetchManifest(entry: ScriptPluginRegistryEntry): Result<ScriptPluginManifest> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(entry.manifestUrl.startsWith("https://")) {
                    "Module manifests must be served over HTTPS"
                }
                val manifest = ScriptPluginManifest.json
                    .decodeFromString<ScriptPluginManifest>(httpGet(entry.manifestUrl))
                validate(manifest)
                manifest
            }
        }

    /**
     * Persists [manifest] as installed, granting exactly the permissions it
     * declared and the user approved, then reloads the engine.
     */
    suspend fun install(
        manifest: ScriptPluginManifest,
        sourceUrl: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            validate(manifest)
            dao.upsert(
                ScriptPluginEntity(
                    id = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    author = manifest.author,
                    description = manifest.description,
                    manifestJson = ScriptPluginManifest.json.encodeToString(manifest),
                    grantedPermissions = manifest.permissions.joinToString(","),
                    enabled = true,
                    sourceUrl = sourceUrl,
                ),
            )
            reloadEnabled()
            Unit
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled)
        reloadEnabled()
    }

    suspend fun uninstall(id: String) {
        dao.delete(id)
        reloadEnabled()
    }

    /**
     * Loads every enabled module and republishes its tools.
     *
     * Previously registered tools are unregistered first so a disabled or
     * uninstalled module's tools stop being offered to the model immediately,
     * rather than lingering until the next process restart.
     */
    suspend fun reloadEnabled(): List<String> {
        registeredToolNames.forEach { name ->
            runCatching { toolRegistry.unregister(name) }
        }
        registeredToolNames.clear()

        val installed = dao.getEnabled()
        val specs = installed.mapNotNull { entity ->
            runCatching {
                val manifest = ScriptPluginManifest.json
                    .decodeFromString<ScriptPluginManifest>(entity.manifestJson)
                ScriptPluginEngine.PluginSpec(
                    id = manifest.id,
                    source = manifest.main,
                    // Grant only what was approved at install time, not whatever
                    // the manifest happens to ask for now.
                    permissions = entity.grantedPermissions
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet(),
                )
            }.onFailure {
                Timber.tag(TAG).w(it, "Could not prepare module %s", entity.id)
            }.getOrNull()
        }

        val failures = engine.reload(specs)

        installed.forEach { entity ->
            val manifest = runCatching {
                ScriptPluginManifest.json.decodeFromString<ScriptPluginManifest>(entity.manifestJson)
            }.getOrNull() ?: return@forEach

            // Only publish a tool the script actually registered: a manifest may
            // declare a tool whose registerTool call never ran.
            val live = engine.registeredToolNames(manifest.id).toSet()
            manifest.tools.filter { it.name in live }.forEach { spec ->
                runCatching {
                    toolRegistry.register(ScriptPluginTool(spec.toDescriptor(), manifest.id, engine))
                    registeredToolNames += spec.name
                }.onFailure {
                    Timber.tag(TAG).w(it, "Could not register %s from %s", spec.name, manifest.id)
                }
            }
        }
        return failures
    }

    private fun validate(manifest: ScriptPluginManifest) {
        require(manifest.id.isNotBlank()) { "Module id is required" }
        require(manifest.type == ScriptPluginManifest.TYPE_TOOL) {
            "Unsupported module type '${manifest.type}'"
        }
        require(manifest.main.isNotBlank()) { "Module has no script" }
        require(manifest.tools.isNotEmpty()) { "Module declares no tools" }
        manifest.tools.forEach { tool ->
            require(tool.name.isNotBlank()) { "Every tool needs a name" }
        }
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} from $url")
            }
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        private const val TAG = "ScriptPluginRepo"

        /** Public module index. Pre-filled in the Modules screen. */
        const val DEFAULT_REGISTRY_URL =
            "https://raw.githubusercontent.com/l3ad3r1/hermes-jeeves-modules/main/registry.json"
    }
}
