package com.hermes.agent.data.backup

import com.hermes.agent.BuildConfig
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.domain.repository.SkillRepository
import kotlinx.coroutines.Dispatchers
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
    private val json: Json,
) {

    sealed class BackupResult {
        data class Success(val gistId: String, val timestamp: Long) : BackupResult()
        data class Failure(val message: String) : BackupResult()
    }

    sealed class RestoreResult {
        data class Success(val memoriesImported: Int, val skillsImported: Int) : RestoreResult()
        data class Failure(val message: String) : RestoreResult()
    }

    suspend fun backup(pat: String, existingGistId: String?): BackupResult =
        withContext(Dispatchers.IO) {
            if (pat.isBlank()) return@withContext BackupResult.Failure("GitHub PAT is not configured.")

            // Serialize current state.
            val memories = runCatching { memoryRepository.searchMemories("", limit = 1000) }
                .getOrDefault(emptyList())
                .map { MemoryBackup(it.content, it.createdAt) }

            val skills = runCatching { skillRepository.getAll() }
                .getOrDefault(emptyList())
                .map {
                    SkillBackup(it.name, it.description, it.content,
                        it.category, it.tags, it.version, it.isBuiltIn)
                }

            val backupData = BackupData(
                exportedAt = System.currentTimeMillis(),
                memories = memories,
                skills = skills,
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

            for (m in backupData.memories) {
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

            Timber.tag("GithubBackup").i("restored $memoriesImported memories, $skillsImported skills")
            RestoreResult.Success(memoriesImported, skillsImported)
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
        append("\"description\":\"Hermes Agent backup\",")
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
