package com.hermes.agent.data.export

import android.content.Context
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.util.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports the on-device conversation history into the JSON layout that
 * NousResearch/hermes-agent-self-evolution's `HermesSessionImporter` expects
 * (one JSON file per conversation under `~/.hermes/sessions/`), so the offline
 * evolution tool can mine real device traces to build eval datasets.
 *
 * Each conversation becomes one `{id}.json`:
 * ```
 * { "session_id": "<id>", "title": "<title>",
 *   "messages": [ { "role": "user"|"assistant"|"tool", "content": "..." }, ... ] }
 * ```
 * Files are staged under `getExternalFilesDir/session-export/sessions/` and
 * zipped to `session-export/hermes-sessions.zip` for a single share. Unzip the
 * archive into `~/.hermes/sessions/` on the dev machine, then run e.g.
 * `python -m evolution.skills.evolve_skill --skill X --eval-source sessiondb`.
 *
 * Note: message content is exported verbatim. The Python importer strips
 * secrets when mining; the archive itself is not redacted, so treat it as
 * sensitive.
 */
@Singleton
class SessionExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val dispatchers: DispatcherProvider,
) {

    data class ExportResult(
        val zipFile: File,
        val sessionCount: Int,
        val messageCount: Int,
    )

    suspend fun exportAll(): ExportResult = withContext(dispatchers.io) {
        val base = File(context.getExternalFilesDir(null), "session-export")
        val staging = File(base, "sessions")
        // Clear any previous export so stale conversations don't linger.
        staging.deleteRecursively()
        staging.mkdirs()

        val conversations = conversationDao.observeAll().first()
        var sessionCount = 0
        var messageCount = 0

        for (conv in conversations) {
            val messages = messageDao.observeByConversation(conv.id).first()
            if (messages.isEmpty()) continue

            val arr = JSONArray()
            for (m in messages) {
                arr.put(
                    JSONObject()
                        .put("role", m.role)        // already OpenAI wire name
                        .put("content", m.content),
                )
                messageCount++
            }

            val obj = JSONObject()
                .put("session_id", conv.id)
                .put("title", conv.title)
                .put("messages", arr)

            File(staging, "${conv.id}.json").writeText(obj.toString())
            sessionCount++
        }

        val zip = File(base, "hermes-sessions.zip")
        zipDirectory(staging, zip)

        Timber.tag("SessionExport").i("exported $sessionCount sessions ($messageCount messages) -> ${zip.name}")
        ExportResult(zip, sessionCount, messageCount)
    }

    private fun zipDirectory(dir: File, zipFile: File) {
        if (zipFile.exists()) zipFile.delete()
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
            for (f in files) {
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
