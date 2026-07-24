package com.hermes.agent.data.backup

import android.content.Context
import com.hermes.agent.BuildConfig
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.domain.model.ScheduledTask
import com.hermes.agent.domain.repository.CronRepository
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.work.CronScheduler
import com.l3ad3r1.octojotter.data.repository.NoteRepository
import com.sassybutler.alarm.Alarm
import com.sassybutler.alarm.AlarmStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backs up memories + skills to a private GitHub Gist.
 *
 * Authentication: a personal access token (PAT) with the `gist` scope.
 * The PAT is stored in DataStore (same tier as the cloud API key).
 *
 * First backup: creates a new secret gist and returns its ID.
 * Subsequent backups: PATCHes the existing gist.
 * Restore: GETs the gist by ID and re-imports all records.
 *
 * The gist contains a single file — `hermes-backup.json`.
 */
@Singleton
class GithubBackupService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val memoryRepository: MemoryRepository,
    private val skillRepository: SkillRepository,
    private val settingsRepository: SettingsRepository,
    private val cronRepository: CronRepository,
    private val cronScheduler: CronScheduler,
    private val noteRepository: NoteRepository,
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    sealed class BackupResult {
        data class Success(val gistId: String, val timestamp: Long) : BackupResult()
        data class Failure(val message: String) : BackupResult()
    }

    sealed class RestoreResult {
        data class Success(
            val memoriesImported: Int,
            val skillsImported: Int,
            val settingsRestored: Boolean,
            val cronsImported: Int,
            val notesImported: Int,
            val alarmsImported: Int,
        ) : RestoreResult()
        data class Failure(val message: String) : RestoreResult()
    }

    suspend fun backup(pat: String, existingGistId: String?): BackupResult =
        withContext(Dispatchers.IO) {
            if (pat.isBlank()) return@withContext BackupResult.Failure("GitHub PAT is not configured.")

            // Serialize current state. observeMemories reads ALL rows —
            // searchMemories("") ranked by similarity to an empty-string
            // embedding and could silently omit memories (audit M1).
            val memories = runCatching { memoryRepository.observeMemories().first() }
                .getOrDefault(emptyList())
                .map { MemoryBackup(it.content, it.createdAt) }

            val skills = runCatching { skillRepository.getAll() }
                .getOrDefault(emptyList())
                .map {
                    SkillBackup(it.name, it.description, it.content,
                        it.category, it.tags, it.version, it.isBuiltIn)
                }

            val s = runCatching { settingsRepository.current() }.getOrNull()
            val settingsBackup = s?.let {
                SettingsBackup(
                    cloudEnabled = it.cloudEnabled,
                    cloudBaseUrl = it.cloudBaseUrl,
                    cloudModel = it.cloudModel,
                    reasoningEffort = it.reasoningEffort,
                    auxModel = it.auxModel,
                    auxBaseUrl = it.auxBaseUrl,
                    appTheme = it.appTheme,
                )
            }

            val crons = runCatching { cronRepository.observe().first() }
                .getOrDefault(emptyList())
                .map {
                    CronBackup(
                        id = it.id,
                        label = it.label,
                        prompt = it.prompt,
                        cronExpression = it.cronExpression,
                        isEnabled = it.isEnabled,
                        createdAt = it.createdAt,
                    )
                }

            val notes = runCatching { noteRepository.getAllNotes() }
                .getOrDefault(emptyList())
                .mapNotNull { it.toBackupOrNull() }

            val alarms = runCatching { AlarmStore.all(context) }
                .getOrDefault(emptyList())
                .map {
                    AlarmBackup(
                        id = it.id,
                        hour = it.hour,
                        minute = it.minute,
                        label = it.label,
                        enabled = it.enabled,
                        days = it.days,
                    )
                }

            val backupData = BackupData(
                exportedAt = System.currentTimeMillis(),
                memories = memories,
                skills = skills,
                settings = settingsBackup,
                crons = crons,
                notes = notes,
                alarms = alarms,
            )

            val payload = buildGistPayload(json.encodeToString(backupData))
            val body = payload.toRequestBody("application/json".toMediaType())

            val ts = System.currentTimeMillis()
            return@withContext if (existingGistId.isNullOrBlank()) {
                createGist(pat, body, ts)
            } else {
                updateGist(pat, existingGistId, body, ts)
            }
        }

    suspend fun restore(pat: String, gistId: String): RestoreResult =
        withContext(Dispatchers.IO) {
            if (pat.isBlank()) return@withContext RestoreResult.Failure("GitHub PAT is not configured.")
            if (gistId.isBlank()) return@withContext RestoreResult.Failure("No backup found. Run a backup first.")

            val request = Request.Builder()
                .url("https://api.github.com/gists/$gistId")
                .header("Authorization", "Bearer $pat")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Hermes-Agent-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            val responseBody = runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val hint = githubErrorHint(response.code, bodyStr)
                        return@withContext RestoreResult.Failure("GitHub ${response.code}: $hint")
                    }
                    bodyStr
                }
            }.onFailure { Timber.tag("GithubBackup").w(it, "restore network error") }
                .getOrNull() ?: return@withContext RestoreResult.Failure("Network error during restore.")

            val content = runCatching {
                JSONObject(responseBody)
                    .getJSONObject("files")
                    .getJSONObject(GIST_FILENAME)
                    .getString("content")
            }.onFailure { Timber.tag("GithubBackup").w(it, "parse gist content") }
                .getOrNull() ?: return@withContext RestoreResult.Failure("Could not parse gist content.")

            val backupData = runCatching { json.decodeFromString<BackupData>(content) }
                .onFailure { Timber.tag("GithubBackup").w(it, "decode backup JSON") }
                .getOrNull() ?: return@withContext RestoreResult.Failure("Invalid backup format.")

            var memoriesImported = 0
            var skillsImported = 0
            var notesImported = 0
            var alarmsImported = 0

            val existingMemories = runCatching { memoryRepository.observeMemories().first() }.getOrDefault(emptyList())
            val existingMemoryContents = existingMemories.map { it.content }.toSet()

            for (m in backupData.memories) {
                if (existingMemoryContents.contains(m.content)) {
                    Timber.tag("GithubBackup").d("Skipping duplicate memory: ${m.content.take(20)}...")
                    continue
                }
                runCatching { memoryRepository.addMemory(m.content) }
                    .onSuccess { memoriesImported++ }
                    .onFailure { Timber.tag("GithubBackup").w(it, "import memory") }
            }

            for (s in backupData.skills) {
                if (s.isBuiltIn) continue
                // Skills Guard: gist content is remote input — skip skills
                // carrying injection/exfiltration/destructive instructions.
                val verdict = com.hermes.agent.domain.skill.SkillGuard.vet(s.content)
                if (!verdict.ok) {
                    Timber.tag("GithubBackup").w(
                        "skipping restored skill '${s.name}' — Skills Guard: ${verdict.flags.joinToString()}",
                    )
                    continue
                }
                runCatching {
                    skillRepository.upsert(
                        name = s.name,
                        description = s.description,
                        content = s.content,
                        category = s.category,
                        tags = s.tags,
                        version = s.version,
                    )
                }
                    .onSuccess { skillsImported++ }
                    .onFailure { Timber.tag("GithubBackup").w(it, "import skill ${s.name}") }
            }

            // Restore Cloud LLM + app settings. PAT/Gist ID are intentionally not
            // touched — they're the credentials being used for this restore.
            var settingsRestored = false
            backupData.settings?.let { sb ->
                runCatching {
                    settingsRepository.setCloudEnabled(sb.cloudEnabled)
                    // v2-v4 backups may contain credentials. New v5 backups do not.
                    // A blank field must not erase a key configured on this device.
                    if (sb.cloudApiKey.isNotBlank()) settingsRepository.setCloudApiKey(sb.cloudApiKey)
                    if (sb.cloudBaseUrl.isNotBlank()) settingsRepository.setCloudBaseUrl(sb.cloudBaseUrl)
                    if (sb.cloudModel.isNotBlank()) settingsRepository.setCloudModel(sb.cloudModel)
                    if (sb.reasoningEffort.isNotBlank()) settingsRepository.setReasoningEffort(sb.reasoningEffort)
                    if (sb.auxModel.isNotBlank()) settingsRepository.setAuxModel(sb.auxModel)
                    settingsRepository.setAuxBaseUrl(sb.auxBaseUrl)
                    if (sb.auxApiKey.isNotBlank()) settingsRepository.setAuxApiKey(sb.auxApiKey)
                    if (sb.appTheme.isNotBlank()) settingsRepository.setAppTheme(sb.appTheme)
                }
                    .onSuccess { settingsRestored = true }
                    .onFailure { Timber.tag("GithubBackup").w(it, "restore settings") }
            }

            // Restore cron jobs. add() upserts by id, so restoring twice is idempotent.
            var cronsImported = 0
            for (c in backupData.crons) {
                runCatching {
                    val task = ScheduledTask(
                        id = c.id,
                        label = c.label,
                        prompt = c.prompt,
                        cronExpression = c.cronExpression,
                        isEnabled = c.isEnabled,
                        createdAt = c.createdAt,
                    )
                    cronRepository.add(task)
                    // Re-enqueue the WorkManager job so restored jobs actually fire.
                    if (task.isEnabled) cronScheduler.schedule(task)
                }
                    .onSuccess { cronsImported++ }
                    .onFailure { Timber.tag("GithubBackup").w(it, "import cron ${c.label}") }
            }

            // NoteEntity ids auto-generate, so a blind insert duplicates every note
            // each time Restore is tapped. Skip notes that already exist (matched by
            // gistId when present, else exact title+content).
            val existingNotes = runCatching { noteRepository.getAllNotes() }
                .getOrDefault(emptyList())
                .toMutableList()
            for (n in backupData.notes) {
                val alreadyPresent = existingNotes.any {
                    (n.gistId != null && it.gistId == n.gistId) ||
                        (n.repository != null && n.path != null &&
                            it.repository == n.repository && it.path == n.path) ||
                        (n.gistId == null && n.repository == null &&
                            it.gistId == null && it.repository == null &&
                            it.title == n.title && it.content == n.content &&
                            it.deletedAt == n.deletedAt)
                }
                if (alreadyPresent) continue
                val entity = n.toRestoredEntity()
                runCatching {
                    noteRepository.insertNote(entity)
                }
                    .onSuccess {
                        notesImported++
                        existingNotes += entity
                    }
                    .onFailure { Timber.tag("GithubBackup").w(it, "import note ${n.title}") }
            }

            // Persist AND schedule, mirroring the cron loop above and Butler's own
            // AddAlarmSheet. AlarmStore.upsert alone leaves restored alarms silent
            // until the next reboot (AlarmReceiver re-registers stored alarms then).
            val alarmScheduler = com.sassybutler.alarm.AlarmScheduler(context)
            for (a in backupData.alarms) {
                runCatching {
                    val alarm = Alarm(
                        id = a.id,
                        hour = a.hour,
                        minute = a.minute,
                        label = a.label,
                        enabled = a.enabled,
                        days = a.days,
                    )
                    AlarmStore.upsert(context, alarm)
                    if (alarm.enabled) alarmScheduler.schedule(alarm)
                    
                    // L5: Mirror restored alarms to calendar if permission is granted
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        com.sassybutler.alarm.CalendarSyncManager.syncAlarmToCalendar(context, alarm)
                    }
                }
                    .onSuccess { alarmsImported++ }
                    .onFailure { Timber.tag("GithubBackup").w(it, "import alarm ${a.label}") }
            }

            Timber.tag("GithubBackup").i(
                "restored $memoriesImported memories, $skillsImported skills, " +
                    "settings=$settingsRestored, $cronsImported crons, " +
                    "$notesImported notes, $alarmsImported alarms",
            )
            RestoreResult.Success(memoriesImported, skillsImported, settingsRestored, cronsImported, notesImported, alarmsImported)
        }

    private fun createGist(pat: String, body: okhttp3.RequestBody, ts: Long): BackupResult {
        val request = Request.Builder()
            .url("https://api.github.com/gists")
            .header("Authorization", "Bearer $pat")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Hermes-Agent-Android/${BuildConfig.VERSION_NAME}")
            .post(body)
            .build()

        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val hint = githubErrorHint(response.code, bodyStr)
                    return@runCatching BackupResult.Failure("GitHub ${response.code}: $hint")
                }
                val gistId = JSONObject(bodyStr).optString("id", "")
                if (gistId.isBlank()) BackupResult.Failure("No gist ID in response")
                else BackupResult.Success(gistId, ts)
            }
        }.onFailure { Timber.tag("GithubBackup").w(it, "create gist") }
            .getOrElse { BackupResult.Failure(it.message ?: "Network error") }
    }

    private fun updateGist(
        pat: String,
        gistId: String,
        body: okhttp3.RequestBody,
        ts: Long,
    ): BackupResult {
        val request = Request.Builder()
            .url("https://api.github.com/gists/$gistId")
            .header("Authorization", "Bearer $pat")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Hermes-Agent-Android/${BuildConfig.VERSION_NAME}")
            .patch(body)
            .build()

        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val hint = githubErrorHint(response.code, bodyStr)
                    return@runCatching BackupResult.Failure("GitHub ${response.code}: $hint")
                }
                BackupResult.Success(gistId, ts)
            }
        }.onFailure { Timber.tag("GithubBackup").w(it, "update gist") }
            .getOrElse { BackupResult.Failure(it.message ?: "Network error") }
    }

    private fun githubErrorHint(code: Int, body: String): String = when (code) {
        401 -> "Invalid or expired token. Regenerate your PAT and paste it again."
        403 -> {
            val msg = runCatching { JSONObject(body).optString("message", "") }.getOrDefault("")
            when {
                msg.contains("scope", ignoreCase = true) ||
                    msg.contains("permission", ignoreCase = true) ->
                    "Token is missing the 'gist' scope. Create a new PAT with gist access."
                msg.contains("fine-grained", ignoreCase = true) ->
                    "Fine-grained tokens need explicit Gist read+write permission."
                msg.isNotBlank() -> msg
                else -> "Forbidden. Check your PAT has the 'gist' scope and hasn't expired."
            }
        }
        404 -> "Gist not found. It may have been deleted — clear the Gist ID and back up again."
        422 -> "Request rejected by GitHub. The backup data may be malformed."
        else -> "Unexpected error (HTTP $code). Check your internet connection and try again."
    }

    private fun buildGistPayload(jsonContent: String): String = buildString {
        append("{")
        append("\"description\":\"Jeeves backup\",")
        append("\"public\":false,")
        append("\"files\":{\"$GIST_FILENAME\":{\"content\":")
        // JSONObject.quote() correctly escapes the content string.
        append(JSONObject.quote(jsonContent))
        append("}}")
        append("}")
    }

    companion object {
        const val GIST_FILENAME = "hermes-backup.json"
    }
}
