package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.entity.ConversationEntity
import com.hermes.agent.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session repository implementing Hermes Agent session_search shapes:
 * - Discovery: FTS5 query with ranked results
 * - Scroll: Windowed message retrieval around anchor message
 * - Read: Full session dump by ID
 * - Browse: Recent sessions chronologically
 *
 * Uses raw SQL queries on HermesDatabase for FTS5 (avoiding @Fts5 KSP issues).
 */
@Singleton
class SessionRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val db: androidx.sqlite.db.SupportSQLiteDatabase,
) {

    /**
     * Discovery shape: FTS5 search with ranking.
     * Supports: AND (default), OR, quoted phrases, boolean, prefix wildcards.
     *
     * @param query FTS5 query syntax (e.g., "auth refactor", "alpha OR beta", "\"docker networking\"")
     * @param limit Max results (default 3, max 10 for discovery)
     * @return Ranked list of matching conversations with snippets
     */
    suspend fun searchByQuery(query: String, limit: Int = 3): List<ConversationEntity> {
        // FTS5 query on conversation_fts virtual table, joined with conversations
        val cursor = db.query("""
            SELECT c.* FROM conversation_fts f
            JOIN conversations c ON f.id = c.id
            WHERE conversation_fts MATCH ?
            ORDER BY rank ASC
            LIMIT ?
        """, arrayOf(query, limit.toString()))

        val results = mutableListOf<ConversationEntity>()
        while (cursor.moveToNext()) {
            results.add(ConversationEntity(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                lastMessagePreview = cursor.getString(cursor.getColumnIndexOrThrow("last_message_preview")),
                messageCount = cursor.getInt(cursor.getColumnIndexOrThrow("message_count")),
            ))
        }
        cursor.close()
        return results
    }

    /**
     * Browse shape: Get recent sessions chronologically.
     * No query — just return most recent conversations.
     */
    suspend fun getRecent(limit: Int = 20): List<ConversationEntity> {
        val cursor = db.query("""
            SELECT * FROM conversations
            ORDER BY updated_at DESC
            LIMIT ?
        """, arrayOf(limit.toString()))

        val results = mutableListOf<ConversationEntity>()
        while (cursor.moveToNext()) {
            results.add(ConversationEntity(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                lastMessagePreview = cursor.getString(cursor.getColumnIndexOrThrow("last_message_preview")),
                messageCount = cursor.getInt(cursor.getColumnIndexOrThrow("message_count")),
            ))
        }
        cursor.close()
        return results
    }

    /**
     * Read shape: Get a specific session by ID with all messages.
     * Used for resolving @session:<profile>/<id> links.
     *
     * @param id Session ID
     * @return Conversation with full message list, or null if not found
     */
    suspend fun getSessionById(id: String): SessionWithMessages? {
        val conversation = conversationDao.getById(id) ?: return null
        val messages = messageDao.observeByConversation(id).first() // Get all messages
        return SessionWithMessages(conversation, messages)
    }

    /**
     * Scroll shape: Get window of messages around an anchor message.
     *
     * @param conversationId Session ID
     * @param anchorMessageId Message ID to center the window on
     * @param window Messages to return on each side (anchor itself always included)
     * @return Window of messages with orientation markers
     */
    suspend fun scrollAroundMessage(
        conversationId: String,
        anchorMessageId: Long,
        window: Int = 5,
    ): MessageWindow {
        // Get anchor message index
        val cursor = db.query("""
            SELECT COUNT(*) as idx FROM messages
            WHERE conversation_id = ? AND timestamp <= (
                SELECT timestamp FROM messages WHERE id = ?
            )
        """, arrayOf(conversationId, anchorMessageId.toString()))

        var anchorIndex = 0
        if (cursor.moveToNext()) {
            anchorIndex = cursor.getInt(0) - 1 // 0-indexed
        }
        cursor.close()

        // Get full message list
        val allMessages = messageDao.observeByConversation(conversationId).first()
        
        // Get window around anchor
        val offset = maxOf(0, anchorIndex - window)
        val limit = window * 2 + 1

        val messages: List<MessageEntity> = allMessages.drop(offset).take(limit)

        // Get bookends (first 3 and last 3 messages of session)
        val bookendStart: List<MessageEntity> = allMessages.take(3)
        val bookendEnd: List<MessageEntity> = allMessages.takeLast(3)

        return MessageWindow(
            messages = messages,
            anchorMessageId = anchorMessageId,
            bookendStart = bookendStart,
            bookendEnd = bookendEnd,
            hasMoreBefore = offset > 0,
            hasMoreAfter = offset + limit < allMessages.size,
        )
    }

    /**
     * Rename a session.
     */
    suspend fun rename(id: String, newTitle: String) {
        conversationDao.rename(id, newTitle)
    }

    /**
         * Delete a session.
         */
        suspend fun delete(id: String) {
            conversationDao.delete(id)
        }
    
        /**
         * Alias for delete (for consistency with UI naming).
         */
        suspend fun deleteSession(id: String) = delete(id)
    
        /**
         * Get message count for a session.
         */
        suspend fun getMessageCount(sessionId: String): Int {
            val cursor = db.query(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?",
                arrayOf(sessionId)
            )
            var count = 0
            if (cursor.moveToNext()) {
                count = cursor.getInt(0)
            }
            cursor.close()
            return count
        }

    /**
     * Export session to JSON.
     */
    suspend fun exportToJson(id: String): String {
        val session = getSessionById(id) ?: throw IllegalArgumentException("Session not found: $id")
        // Simple JSON serialization (could use kotlinx.serialization)
        return buildString {
            appendLine("{")
            appendLine("  \"id\": \"${session.conversation.id}\",")
            appendLine("  \"title\": \"${session.conversation.title}\",")
            appendLine("  \"created_at\": ${session.conversation.createdAt},")
            appendLine("  \"updated_at\": ${session.conversation.updatedAt},")
            appendLine("  \"messages\": [")
            session.messages.forEachIndexed { idx, msg ->
                append("    {\"id\": ${msg.id}, \"role\": \"${msg.role}\", \"content\": \"${msg.content.replace("\"", "\\\"")}\", \"timestamp\": ${msg.timestamp}}")
                if (idx < session.messages.lastIndex) appendLine(",")
                else appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }
    }

    /**
     * Prune old sessions (older than N days).
     */
    suspend fun pruneOlderThan(days: Int): Int {
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        var count = 0
        val cursor = db.query("""
            SELECT id FROM conversations WHERE updated_at < ?
        """, arrayOf(cutoff.toString()))

        val idsToDelete = mutableListOf<String>()
        while (cursor.moveToNext()) {
            idsToDelete.add(cursor.getString(0))
        }
        cursor.close()

        idsToDelete.forEach { id ->
            conversationDao.delete(id)
            count++
        }
        return count
    }

    /**
     * Get session statistics.
     */
    suspend fun getStats(): SessionStats {
        val cursor = db.query("""
            SELECT 
                COUNT(*) as total,
                MIN(created_at) as oldest,
                MAX(updated_at) as newest,
                SUM(message_count) as total_messages
            FROM conversations
        """)

        var total = 0
        var oldest = 0L
        var newest = 0L
        var totalMessages = 0

        if (cursor.moveToNext()) {
            total = cursor.getInt(0)
            oldest = cursor.getLong(1)
            newest = cursor.getLong(2)
            totalMessages = cursor.getInt(3)
        }
        cursor.close()

        return SessionStats(
            totalSessions = total,
            oldestSessionAt = oldest,
            newestSessionAt = newest,
            totalMessages = totalMessages,
        )
    }

    /**
     * Session with full message history.
     */
    data class SessionWithMessages(
        val conversation: ConversationEntity,
        val messages: List<MessageEntity>,
    )

    /**
     * Windowed message retrieval result.
     */
    data class MessageWindow(
        val messages: List<MessageEntity>,
        val anchorMessageId: Long,
        val bookendStart: List<MessageEntity>,
        val bookendEnd: List<MessageEntity>,
        val hasMoreBefore: Boolean,
        val hasMoreAfter: Boolean,
    )

    /**
     * Session store statistics.
     */
    data class SessionStats(
        val totalSessions: Int,
        val oldestSessionAt: Long,
        val newestSessionAt: Long,
        val totalMessages: Int,
    )
}