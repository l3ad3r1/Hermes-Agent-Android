package com.hermes.agent.service

import android.content.Context
import com.hermes.agent.data.local.entity.MessageEntity
import com.hermes.agent.data.repository.SessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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

        // Create new conversation with imported data
        // Note: SessionRepository needs a create method - for now, return placeholder
        // TODO: Implement conversation creation in SessionRepository
        throw NotImplementedError(
            "Session import requires ConversationDao.insert() - not yet implemented. " +
            "Export functionality works; import pending DAO extension."
        )
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
     * Export session to user-accessible Downloads folder.
     * Requires MANAGE_EXTERNAL_STORAGE or SAF for Android 11+.
     */
    suspend fun exportToDownloads(sessionId: String): String? = withContext(Dispatchers.IO) {
        // Note: Android 11+ requires Storage Access Framework for Downloads access
        // This is a placeholder - real implementation needs SAF intent
        val internalPath = exportSession(sessionId)
        
        // For now, just return internal path
        // TODO: Implement SAF export for Android 11+
        internalPath
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