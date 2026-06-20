package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginInstance
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginRegistry
import com.hermes.agent.domain.plugin.PluginResourceUsage
import com.hermes.agent.domain.plugin.PluginSandbox
import com.hermes.agent.domain.plugin.PluginState
import com.hermes.agent.domain.tool.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [PluginRegistry] implementation.
 *
 * Maintains an in-memory map of plugin id → [PluginInstance] and
 * delegates load/unload operations to the appropriate [PluginSandbox]
 * (in-process for first-party, gRPC for third-party).
 *
 * Phase 4 will persist install state to Room so plugins survive process
 * restart; Phase 3 keeps everything in memory for simplicity.
 */
@Singleton
class PluginRegistryImpl @Inject constructor(
    private val inProcessSandbox: InProcessPluginSandbox,
    private val grpcSandbox: GrpcPluginSandbox,
    private val pluginContext: PluginContext,
    private val resourceMonitor: PluginResourceMonitor,
) : PluginRegistry {

    private val mutex = Mutex()
    private val _plugins = MutableStateFlow<List<PluginInstance>>(emptyList())
    override fun observePlugins(): StateFlow<List<PluginInstance>> = _plugins.asStateFlow()

    private val loadedPlugins = mutableMapOf<String, Plugin>()

    override suspend fun activePlugins(): List<Plugin> = mutex.withLock {
        loadedPlugins.values.toList()
    }

    override suspend fun activeToolDescriptors(): List<ToolDescriptor> = mutex.withLock {
        loadedPlugins.values.flatMap { it.manifest.capabilities.flatMap { c -> c.toolDescriptors } }
    }

    override suspend fun byId(id: String): PluginInstance? = mutex.withLock {
        _plugins.value.firstOrNull { it.manifest.id == id }
    }

    override suspend fun install(plugin: Plugin): PluginInstance = mutex.withLock {
        val instance = PluginInstance(
            manifest = plugin.manifest,
            state = PluginState.INSTALLED,
        )
        _plugins.value = (_plugins.value.filterNot { it.manifest.id == plugin.manifest.id } + instance)
            .sortedBy { it.manifest.displayName }
        Timber.tag("PluginRegistry").i("installed %s", plugin.manifest.id)
        instance
    }

    override suspend fun activate(id: String): PluginLifecycleResult = mutex.withLock {
        val plugin = loadedPlugins[id]
        val instance = _plugins.value.firstOrNull { it.manifest.id == id }
            ?: return@withLock PluginLifecycleResult.Failure("unknown plugin: $id")

        if (plugin != null) {
            // Already loaded — resume instead.
            return@withLock updateState(id, PluginState.ACTIVE)
        }

        // First-party plugins (those shipped in-process) use the in-process sandbox.
        // Phase 3.x: route to grpcSandbox for plugins with manifest.id not in the
        // first-party set.
        val sandbox = inProcessSandbox
        val result = sandbox.load(loadedPluginFor(id) ?: return@withLock PluginLifecycleResult.Failure("plugin $id not registered"), pluginContext)
        if (result is PluginLifecycleResult.Success) {
            loadedPlugins[id] = loadedPluginFor(id)!!
            updateState(id, PluginState.ACTIVE, loadedAt = System.currentTimeMillis())
            resourceMonitor.startMonitoring(id)
        } else {
            val msg = (result as PluginLifecycleResult.Failure).message
            updateState(id, PluginState.ERROR, lastError = msg)
        }
        result
    }

    override suspend fun suspend_(id: String): PluginLifecycleResult = mutex.withLock {
        val plugin = loadedPlugins[id] ?: return@withLock PluginLifecycleResult.Failure("plugin $id not active")
        val result = inProcessSandbox.suspend_(plugin)
        if (result is PluginLifecycleResult.Success) {
            updateState(id, PluginState.SUSPENDED)
            resourceMonitor.stopMonitoring(id)
        }
        result
    }

    override suspend fun uninstall(id: String) = mutex.withLock {
        val plugin = loadedPlugins.remove(id)
        if (plugin != null) {
            inProcessSandbox.unload(plugin)
            resourceMonitor.stopMonitoring(id)
        }
        _plugins.value = _plugins.value.filterNot { it.manifest.id == id }
        Timber.tag("PluginRegistry").i("uninstalled %s", id)
    }

    // --- helpers ---

    /**
     * Map manifest id → live Plugin instance. Phase 3 only has the
     * first-party set; Phase 3.x will add a discovery mechanism for
     * third-party APKs.
     */
    private val firstPartyPlugins = mutableMapOf<String, Plugin>()

    /** Called by [com.hermes.agent.di.PluginsModule] at app startup. */
    fun registerFirstParty(plugin: Plugin) {
        firstPartyPlugins[plugin.manifest.id] = plugin
        // Auto-install on registration so the Plugins UI shows it immediately.
        val instance = PluginInstance(
            manifest = plugin.manifest,
            state = PluginState.INSTALLED,
        )
        _plugins.value = (_plugins.value.filterNot { it.manifest.id == plugin.manifest.id } + instance)
            .sortedBy { it.manifest.displayName }
    }

    private fun loadedPluginFor(id: String): Plugin? = firstPartyPlugins[id]

    private fun updateState(
        id: String,
        state: PluginState,
        loadedAt: Long? = null,
        lastError: String? = null,
    ): PluginLifecycleResult {
        _plugins.value = _plugins.value.map { inst ->
            if (inst.manifest.id == id) {
                inst.copy(
                    state = state,
                    loadedAt = loadedAt ?: inst.loadedAt,
                    lastError = lastError ?: (if (state == PluginState.ERROR) inst.lastError else null),
                )
            } else inst
        }
        return PluginLifecycleResult.Success
    }
}
