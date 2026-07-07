package com.hermes.agent.tool

import com.hermes.agent.data.repository.SessionRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SessionSearchTool - LLM-callable tool for session_search parity.
 * 
 * Implements the 4 shapes from Hermes Agent's session_search:
 * 1. Discovery: Search with FTS5 query, return ranked results
 * 2. Scroll: Get messages around an anchor with bookends
 * 3. Read: Load complete session by ID with all messages
 * 4. Browse: List recent sessions chronologically
 */
@Singleton
class SessionSearchTool @Inject constructor(
    private val sessionRepository: SessionRepository,
) : Tool {

    companion object {
        private val JSON = Json { isLenient = true; ignoreUnknownKeys = true }

        val DESCRIPTOR = ToolDescriptor(
            name = "session_search",
            description = """
                Search, browse, and retrieve past conversation sessions.
                Supports 4 shapes:
                - discovery: FTS5 search with query (e.g., "auth refactor")
                - scroll: Get messages around an anchor point (±N window)
                - read: Load complete session by ID with all messages  
                - browse: List recent sessions chronologically
            """.trimIndent(),
            parameters = listOf(
                ToolParameter(
                    name = "shape",
                    type = ToolParameterType.STRING,
                    description = "Search shape: discovery|scroll|read|browse",
                    required = true,
                    enumValues = listOf("discovery", "scroll", "read", "browse"),
                ),
                ToolParameter(
                    name = "query",
                    type = ToolParameterType.STRING,
                    description = "FTS5 search query for discovery shape",
                    required = false,
                ),
                ToolParameter(
                    name = "session_id",
                    type = ToolParameterType.STRING,
                    description = "Session ID for scroll/read shapes",
                    required = false,
                ),
                ToolParameter(
                    name = "anchor_message_id",
                    type = ToolParameterType.INTEGER,
                    description = "Message ID to center scroll window on",
                    required = false,
                ),
                ToolParameter(
                    name = "window",
                    type = ToolParameterType.INTEGER,
                    description = "Messages on each side of anchor (default: 5)",
                    required = false,
                ),
                ToolParameter(
                    name = "limit",
                    type = ToolParameterType.INTEGER,
                    description = "Max results for discovery/browse (default: 3)",
                    required = false,
                ),
            ),
            category = "productivity",
        )
    }

    override val descriptor: ToolDescriptor = DESCRIPTOR

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        return try {
            val startTime = System.currentTimeMillis()
            
            val shape = arguments.getString("shape") 
                ?: return ToolResult.error("Missing required parameter: shape", 0L)

            val result = when (shape) {
                "discovery" -> {
                    val query = arguments.getString("query") ?: ""
                    val limit = arguments.getInt("limit") ?: 3
                    handleDiscovery(query, limit)
                }
                "scroll" -> {
                    val sessionId = arguments.getString("session_id") 
                        ?: return ToolResult.error("session_id required for scroll shape", 0L)
                    val anchorId = arguments.getLong("anchor_message_id")
                        ?: return ToolResult.error("anchor_message_id required for scroll shape", 0L)
                    val window = arguments.getInt("window") ?: 5
                    handleScroll(sessionId, anchorId, window)
                }
                "read" -> {
                    val sessionId = arguments.getString("session_id")
                        ?: return ToolResult.error("session_id required for read shape", 0L)
                    handleRead(sessionId)
                }
                "browse" -> {
                    val limit = arguments.getInt("limit") ?: 20
                    handleBrowse(limit)
                }
                else -> return ToolResult.error("Unknown shape: $shape. Must be: discovery|scroll|read|browse", 0L)
            }

            val executionMs = System.currentTimeMillis() - startTime
            ToolResult.ok(output = JSON.encodeToString(SessionSearchResult.serializer(), result), executionMs = executionMs)
        } catch (e: IllegalArgumentException) {
            ToolResult.error("Invalid arguments: ${e.message}", 0L)
        } catch (e: Exception) {
            ToolResult.error("Session search failed: ${e.message}", 0L)
        }
    }

    private suspend fun handleDiscovery(query: String, limit: Int): SessionSearchResult {
        if (query.isBlank()) {
            return SessionSearchResult.Discovery(emptyList(), "Empty query")
        }
        val sessions = sessionRepository.searchByQuery(query, limit)
        return SessionSearchResult.Discovery(
            sessions = sessions.map { s ->
                SessionSummary(s.id, s.title, s.createdAt, s.updatedAt, s.messageCount, s.lastMessagePreview)
            },
            query = query,
        )
    }

    private suspend fun handleScroll(sessionId: String, anchorId: Long, window: Int): SessionSearchResult {
        val result = sessionRepository.scrollAroundMessage(sessionId, anchorId, window)
        val toSummary: (com.hermes.agent.data.local.entity.MessageEntity) -> MessageSummary = { m ->
            MessageSummary(m.id.toString(), m.role, m.content, m.timestamp)
        }
        return SessionSearchResult.Scroll(
            messages = result.messages.map(toSummary),
            anchor_message_id = anchorId,
            has_more_before = result.hasMoreBefore,
            has_more_after = result.hasMoreAfter,
            bookend_start = result.bookendStart.map(toSummary),
            bookend_end = result.bookendEnd.map(toSummary),
        )
    }

    private suspend fun handleRead(sessionId: String): SessionSearchResult {
        val session = sessionRepository.getSessionById(sessionId)
            ?: return SessionSearchResult.Error("Session not found: $sessionId")
        val toSummary: (com.hermes.agent.data.local.entity.MessageEntity) -> MessageSummary = { m ->
            MessageSummary(m.id.toString(), m.role, m.content, m.timestamp)
        }
        return SessionSearchResult.FullSession(
            id = session.conversation.id,
            title = session.conversation.title,
            created_at = session.conversation.createdAt,
            updated_at = session.conversation.updatedAt,
            messages = session.messages.map(toSummary),
        )
    }

    private suspend fun handleBrowse(limit: Int): SessionSearchResult {
        val sessions = sessionRepository.getRecent(limit)
        return SessionSearchResult.Browse(
            sessions = sessions.map { s ->
                SessionSummary(s.id, s.title, s.createdAt, s.updatedAt, s.messageCount, s.lastMessagePreview)
            },
        )
    }

    // Helper extensions
    private fun Map<String, JsonElement>.getString(key: String): String? =
        this[key]?.jsonPrimitive?.content

    private fun Map<String, JsonElement>.getInt(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()

    private fun Map<String, JsonElement>.getLong(key: String): Long? =
        this[key]?.jsonPrimitive?.content?.toLongOrNull()

    // === Result Classes ===

    @kotlinx.serialization.Serializable
    sealed class SessionSearchResult {
        @kotlinx.serialization.Serializable
        data class Discovery(val sessions: List<SessionSummary>, val query: String) : SessionSearchResult()

        @kotlinx.serialization.Serializable
        data class Scroll(
            val messages: List<MessageSummary>,
            val anchor_message_id: Long,
            val has_more_before: Boolean,
            val has_more_after: Boolean,
            val bookend_start: List<MessageSummary>,
            val bookend_end: List<MessageSummary>,
        ) : SessionSearchResult()

        @kotlinx.serialization.Serializable
        data class FullSession(
            val id: String,
            val title: String,
            val created_at: Long,
            val updated_at: Long,
            val messages: List<MessageSummary>,
        ) : SessionSearchResult()

        @kotlinx.serialization.Serializable
        data class Browse(val sessions: List<SessionSummary>) : SessionSearchResult()

        @kotlinx.serialization.Serializable
        data class Error(val error: String) : SessionSearchResult()
    }

    @kotlinx.serialization.Serializable
    data class SessionSummary(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messageCount: Int,
        val preview: String,
    )

    @kotlinx.serialization.Serializable
    data class MessageSummary(
        val id: String,
        val role: String,
        val content: String,
        val timestamp: Long,
    )
}