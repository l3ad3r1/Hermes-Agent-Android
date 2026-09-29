package com.hermes.agent.data.export

import android.content.Context
import android.util.Base64
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hermes.agent.data.security.KeystoreManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One backup file in the cloud repo. */
data class CloudFile(val path: String, val name: String, val device: String, val sha: String, val size: Long)

/**
 * Naming and pruning of the backups kept in the private GitHub repo, apart from Android so it can
 * be tested. Layout: `backups/<app>/<device>/<app>-<UTC date and time>.hbk`. Each phone or tablet
 * has its own folder, so a backup is never overwritten by another device and any device can
 * restore any other's.
 */
object CloudBackupPolicy {
    const val APP = "hermes"
    const val ROOT = "backups"
    const val SUFFIX = ".hbk"
    const val DEFAULT_KEEP = 10

    /** GitHub's contents API refuses bigger files; stop earlier than its limit. */
    const val MAX_BYTES = 50L * 1024 * 1024

    private val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun isRepo(text: String) = REPO.matches(text.trim())

    /** "Galaxy Tab S9" -> "galaxy-tab-s9"; never empty, so the path always has a device folder. */
    fun deviceSlug(label: String): String =
        label.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "device" }

    fun folder(device: String) = "$ROOT/$APP/${deviceSlug(device)}"

    fun path(device: String, nowMillis: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "${folder(device)}/$APP-${stamp.format(Date(nowMillis))}$SUFFIX"
    }

    /** The files to remove so only the newest [keep] of one device's backups stay. The names sort by date. */
    fun toDelete(names: Collection<String>, keep: Int): List<String> =
        names.filter { it.endsWith(SUFFIX) }.sortedDescending().drop(keep.coerceAtLeast(1))
}

/**
 * The GitHub contents API, just enough to keep backup files in a repo. The token is the user's own
 * fine-grained token for that one repo (Contents: read and write).
 */
class GitHubBackupApi(
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build(),
    private val base: String = "https://api.github.com",
) {
    /** Every backup of this app, across all devices, newest first. An empty repo lists as nothing. */
    fun list(repo: String, token: String): List<CloudFile> {
        // A missing folder just means no backups yet; a missing repo or bad token must be reported.
        call(Request.Builder().url("$base/repos/$repo").header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json").get()).close()
        val devices = listDir(repo, token, "${CloudBackupPolicy.ROOT}/${CloudBackupPolicy.APP}")
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "dir" }
        return devices.flatMap { dir ->
            val device = dir.jsonObject.getValue("name").jsonPrimitive.content
            listDir(repo, token, "${CloudBackupPolicy.ROOT}/${CloudBackupPolicy.APP}/$device")
                .map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "file" && it.getValue("name").jsonPrimitive.content.endsWith(CloudBackupPolicy.SUFFIX) }
                .map {
                    CloudFile(
                        path = it.getValue("path").jsonPrimitive.content,
                        name = it.getValue("name").jsonPrimitive.content,
                        device = device,
                        sha = it.getValue("sha").jsonPrimitive.content,
                        size = it["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                }
        }.sortedByDescending { it.name }
    }

    fun put(repo: String, token: String, path: String, bytes: ByteArray, message: String) {
        require(bytes.size <= CloudBackupPolicy.MAX_BYTES) { "The backup is too big for GitHub (${bytes.size / 1_048_576} MB, the limit here is 50 MB)." }
        val body = buildJsonObject {
            put("message", JsonPrimitive(message))
            put("content", JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)))
        }.toString()
        call(request(repo, token, path).put(body.toRequestBody(JSON))).close()
    }

    fun get(repo: String, token: String, path: String): ByteArray =
        call(request(repo, token, path).header("Accept", "application/vnd.github.raw").get()).use { it.body!!.bytes() }

    fun delete(repo: String, token: String, file: CloudFile) {
        val body = buildJsonObject {
            put("message", JsonPrimitive("Remove old backup ${file.name}"))
            put("sha", JsonPrimitive(file.sha))
        }.toString()
        call(request(repo, token, file.path).delete(body.toRequestBody(JSON))).close()
    }

    private fun listDir(repo: String, token: String, path: String): JsonArray {
        val response = call(request(repo, token, path).get(), allowMissing = true)
        return response.use {
            if (it.code == 404 || it.code == 409) JsonArray(emptyList()) else Json.parseToJsonElement(it.body!!.string()).jsonArray
        }
    }

    private fun request(repo: String, token: String, path: String) = Request.Builder()
        .url("$base/repos/$repo/contents/$path")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun call(builder: Request.Builder, allowMissing: Boolean = false): okhttp3.Response {
        val response = http.newCall(builder.build()).execute()
        if (response.isSuccessful || (allowMissing && (response.code == 404 || response.code == 409))) return response
        val code = response.code
        val detail = runCatching {
            Json.parseToJsonElement(response.body!!.string()).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull()
        response.close()
        error(
            when (code) {
                401 -> "GitHub rejected the token. Make a new one with Contents read and write on this repo."
                403 -> "GitHub refused: ${detail ?: "the token cannot write to this repo."}"
                404 -> "GitHub cannot find that repo, or the token has no access to it."
                else -> "GitHub said $code${detail?.let { ": $it" } ?: ""}."
            },
        )
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** What the user chose for the cloud copy. The token and password are sealed with a Keystore key. */
@Singleton
class CloudBackupStore @Inject constructor(
    @ApplicationContext context: Context,
    private val keystore: KeystoreManager,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var repo: String
        get() = prefs.getString("repo", "") ?: ""
        set(v) = prefs.edit().putString("repo", v.trim()).apply()

    var device: String
        get() = prefs.getString("device", null) ?: android.os.Build.MODEL.orEmpty().ifBlank { "device" }
        set(v) = prefs.edit().putString("device", v.trim()).apply()

    var auto: Boolean
        get() = prefs.getBoolean("auto", false)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    var keep: Int
        get() = prefs.getInt("keep", CloudBackupPolicy.DEFAULT_KEEP)
        set(v) = prefs.edit().putInt("keep", v.coerceIn(1, 100)).apply()

    var lastAt: Long
        get() = prefs.getLong("last_at", 0L)
        set(v) = prefs.edit().putLong("last_at", v).apply()

    var lastResult: String
        get() = prefs.getString("last_result", "") ?: ""
        set(v) = prefs.edit().putString("last_result", v).apply()

    val hasToken: Boolean get() = prefs.contains("token")
    val hasPassword: Boolean get() = prefs.contains("password")
    val isConfigured: Boolean get() = CloudBackupPolicy.isRepo(repo) && hasToken

    fun setToken(token: String) = seal("token", TOKEN_ALIAS, token.trim())
    fun setPassword(password: String) = seal("password", PASSWORD_ALIAS, password)
    fun token(): String? = unseal("token", TOKEN_ALIAS)
    fun password(): String? = unseal("password", PASSWORD_ALIAS)

    private fun seal(key: String, alias: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val blob = keystore.encrypt(alias, value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private fun unseal(key: String, alias: String): String? = prefs.getString(key, null)?.let {
        runCatching { String(keystore.decrypt(alias, Base64.decode(it, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrNull()
    }

    private companion object {
        const val PREFS = "cloud_backup"
        const val TOKEN_ALIAS = "cloud_backup_token"
        const val PASSWORD_ALIAS = "cloud_backup_password"
    }
}

/** Uploads, lists and restores backups kept in the private repo. */
@Singleton
class CloudBackupService @Inject constructor(
    private val manager: FullBackupManager,
    private val store: CloudBackupStore,
) {
    private val api = GitHubBackupApi()

    /** Backs everything up, encrypted with [password], and keeps the newest [CloudBackupStore.keep] of this device. */
    suspend fun upload(password: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (repo, token) = credentials()
            val bytes = ByteArrayOutputStream().also { manager.backup(it, password) }.toByteArray()
            val path = CloudBackupPolicy.path(store.device, System.currentTimeMillis())
            api.put(repo, token, path, bytes, "Backup from ${store.device}")
            prune(repo, token)
            "Uploaded ${path.substringAfterLast('/')} (${bytes.size / 1024} KB)."
        }.onSuccess { record(it) }.onFailure { record("Failed: ${it.message}") }
    }

    suspend fun list(): Result<List<CloudFile>> = withContext(Dispatchers.IO) {
        runCatching { val (repo, token) = credentials(); api.list(repo, token) }
    }

    /** Downloads [file] and stages it like a picked file; the app must be reopened to finish. */
    suspend fun stage(file: CloudFile, password: String): Result<StagedRestore> = withContext(Dispatchers.IO) {
        runCatching {
            val (repo, token) = credentials()
            manager.stageRestore(ByteArrayInputStream(api.get(repo, token, file.path)), password)
        }
    }

    private fun prune(repo: String, token: String) {
        val mine = api.list(repo, token).filter { it.device == CloudBackupPolicy.deviceSlug(store.device) }
        val doomed = CloudBackupPolicy.toDelete(mine.map { it.name }, store.keep).toSet()
        mine.filter { it.name in doomed }.forEach { runCatching { api.delete(repo, token, it) } }
    }

    private fun credentials(): Pair<String, String> {
        val token = store.token() ?: error("No GitHub token saved.")
        check(CloudBackupPolicy.isRepo(store.repo)) { "Enter the repo as owner/name." }
        return store.repo to token
    }

    private fun record(message: String) {
        store.lastAt = System.currentTimeMillis()
        store.lastResult = message
    }
}

object CloudBackupScheduler {
    private const val WORK = "cloud_backup"
    private const val WORK_NOW = "cloud_backup_now"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Turns the daily upload on or off to match the settings. */
    fun apply(context: Context, store: CloudBackupStore, replace: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!store.auto || !store.isConfigured || !store.hasPassword) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(24, TimeUnit.HOURS)
            .setConstraints(online)
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CloudBackupWorker>().setConstraints(online).build(),
        )
    }
}

@HiltWorker
class CloudBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val service: CloudBackupService,
    private val store: CloudBackupStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val password = store.password() ?: return Result.failure()
        // A failed run is recorded by the service; the next daily run tries again.
        return if (service.upload(password).isSuccess) Result.success() else Result.failure()
    }
}
