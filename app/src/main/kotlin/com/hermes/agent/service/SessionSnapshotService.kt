package com.hermes.agent.service

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.entity.ConversationEntity
import com.hermes.agent.data.local.entity.MessageEntity
import com.hermes.agent.data.repository.SessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session snapshots - save/restore conversation state to JSON files.
 * 
 * Use cases:
 * - Export important conversations for backup
 * - Share sessions between devices
 * - Archive completed projects
 * - Restore accidentally deleted sessions
 * 
 * Storage location: Android app's files directory
 *   - Internal: /data/data/com.hermes.agent.debug/files/sessions/
 *   - Export to user-accessible: Downloads/HermesSessions/
 */
@Singleton
class SessionSnapshotService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) {

    companion object {
        private const val SNAPSHOTS_DIR = "sessions"
        private const val FILE_EXTENSION = ".hermes.json"
        private val JSON_FORMAT = Json { 
            prettyPrint = true 
            isLenient = true
            ignoreUnknownKeys = true
        }
    }

    private val snapshotsDir: File by lazy {
        File(context.filesDir, SNAPSHOTS_DIR).apply { 
            if (!exists()) mkdirs() 
        }
    }

    /**
     * Export a session to JSON file.
     * 
     * @param sessionId Session ID to export
     * @param filename Optional custom filename (default: {sessionId}{timestamp}.hermes.json)
     * @return Absolute path to saved file
     */
    suspend fun exportSession(
        sessionId: String,
        filename: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val session = sessionRepository.getSessionById(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        val snapshot = SessionSnapshot(
            schemaVersion = 1,
            exportedAt = System.currentTimeMillis(),
            sessionId = session.conversation.id,
            title = session.conversation.title,
            createdAt = session.conversation.createdAt,
            updatedAt = session.conversation.updatedAt,
            messageCount = session.messages.size,
            messages = session.messages.map { msg ->
                SnapshotMessage(
                    id = msg.id,
                    role = msg.role,
                    content = msg.content,
                    agentRole = msg.agentRole,
                    timestamp = msg.timestamp,
                )
            },
        )

        val json = JSON_FORMAT.encodeToString(SessionSnapshot.serializer(), snapshot)
        
        val safeFilename = filename ?: "${sessionId}_${System.currentTimeMillis()}$FILE_EXTENSION"
        val file = File(snapshotsDir, safeFilename)
        file.writeText(json)

        file.absolutePath
    }

    /**
     * Import a session from JSON file.
     * 
     * @param filePath Absolute path to .hermes.json file
     * @return New session ID (may differ from original)
     */
    suspend fun importSession(filePath: String): String = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("File not found: $filePath")
        }

        val json = file.readText()
        val snapshot = JSON_FORMAT.decodeFromString(SessionSnapshot.serializer(), json)

        // Validate schema version
        if (snapshot.schemaVersion != 1) {
            throw IllegalStateException("Unsupported schema version: ${snapshot.schemaVersion}")
        }

        // Import as a brand-new conversation (new id, not the original) so
        // re-importing the same file — or importing on a device that already
        // has the original session — never collides with or overwrites
        // existing data.
        val newConversationId = UUID.randomUUID().toString()
        conversationDao.upsert(
            ConversationEntity(
                id = newConversationId,
                title = "${snapshot.title} (imported)".trim(),
                createdAt = snapshot.createdAt,
                updatedAt = System.currentTimeMillis(),
                lastMessagePreview = snapshot.messages.lastOrNull()?.content.orEmpty().take(120),
                messageCount = snapshot.messages.size,
            )
        )
        snapshot.messages.forEach { msg ->
            messageDao.upsert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = newConversationId,
                    role = msg.role,
                    content = msg.content,
                    agentRole = msg.agentRole,
                    timestamp = msg.timestamp,
                )
            )
        }

        newConversationId
    }

    /**
     * List all saved snapshots.
     */
    suspend fun listSnapshots(): List<SnapshotInfo> = withContext(Dispatchers.IO) {
        if (!snapshotsDir.exists()) return@withContext emptyList()

        snapshotsDir
            .listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val json = file.readText()
                    val snapshot = JSON_FORMAT.decodeFromString(SessionSnapshot.serializer(), json)
                    SnapshotInfo(
                        filename = file.name,
                        path = file.absolutePath,
                        sessionId = snapshot.sessionId,
                        title = snapshot.title,
                        messageCount = snapshot.messageCount,
                        exportedAt = snapshot.exportedAt,
                        fileSizeBytes = file.length(),
                    )
                } catch (e: Exception) {
                    null // Skip corrupted files
                }
            }
            ?.sortedByDescending { it.exportedAt }
            ?: emptyList()
    }

    /**
     * Delete a snapshot.
     */
    suspend fun deleteSnapshot(filename: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(snapshotsDir, filename)
        if (file.exists() && file.extension == "json") {
            file.delete()
        } else {
            false
        }
    }

    /**
     * Export session to the user-accessible Downloads folder.
     *
     * Uses [MediaStore.Downloads] (API 29+, matching this app's minSdk) rather
     * than a raw [File] path or a SAF picker round-trip: it writes into the
     * public Downloads collection under scoped storage without requiring
     * `WRITE_EXTERNAL_STORAGE` or blocking on a user-driven file-picker
     * intent, and the file remains visible to the user (and other apps) in
     * Downloads/HermesSessions afterward.
     *
     * @return the resulting `content://` URI as a string, or null if the
     *   MediaStore insert/write failed.
     */
    suspend fun exportToDownloads(sessionId: String): String? = withContext(Dispatchers.IO) {
        val session = sessionRepository.getSessionById(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        val snapshot = SessionSnapshot(
            schemaVersion = 1,
            exportedAt = System.currentTimeMillis(),
            sessionId = session.conversation.id,
            title = session.conversation.title,
            createdAt = session.conversation.createdAt,
            updatedAt = session.conversation.updatedAt,
            messageCount = session.messages.size,
            messages = session.messages.map { msg ->
                SnapshotMessage(
                    id = msg.id,
                    role = msg.role,
                    content = msg.content,
                    agentRole = msg.agentRole,
                    timestamp = msg.timestamp,
                )
            },
        )
        val json = JSON_FORMAT.encodeToString(SessionSnapshot.serializer(), snapshot)
        val filename = "${sessionId}_${System.currentTimeMillis()}$FILE_EXTENSION"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/HermesSessions")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null
        val wrote = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null
        }.getOrDefault(false)

        if (!wrote) {
            resolver.delete(uri, null, null)
            return@withContext null
        }
        uri.toString()
    }

    /**
     * Get snapshot statistics.
     */
    suspend fun getStats(): SnapshotStats {
        val snapshots = listSnapshots()
        val totalSize = snapshots.sumOf { it.fileSizeBytes }
        val totalMessages = snapshots.sumOf { it.messageCount }

        return SnapshotStats(
            snapshotCount = snapshots.size,
            totalSizeBytes = totalSize,
            totalMessagesSaved = totalMessages,
            oldestSnapshot = snapshots.lastOrNull()?.exportedAt,
            newestSnapshot = snapshots.firstOrNull()?.exportedAt,
        )
    }

    // === Data Classes ===

    @Serializable
    private data class SessionSnapshot(
        val schemaVersion: Int,
        val exportedAt: Long,
        val sessionId: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messageCount: Int,
        val messages: List<SnapshotMessage>,
    )

    @Serializable
    private data class SnapshotMessage(
        val id: String,
        val role: String,
        val content: String,
        val agentRole: String?,
        val timestamp: Long,
    )

    data class SnapshotInfo(
        val filename: String,
        val path: String,
        val sessionId: String,
        val title: String,
        val messageCount: Int,
        val exportedAt: Long,
        val fileSizeBytes: Long,
    )

    data class SnapshotStats(
        val snapshotCount: Int,
        val totalSizeBytes: Long,
        val totalMessagesSaved: Int,
        val oldestSnapshot: Long?,
        val newestSnapshot: Long?,
    )
}