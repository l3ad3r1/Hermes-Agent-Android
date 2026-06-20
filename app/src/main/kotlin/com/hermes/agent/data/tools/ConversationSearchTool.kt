package com.hermes.agent.data.tools

import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search the user's past conversations by keyword. Returns the matching
 * message text along with the conversation id and timestamp so the LLM
 * can cite the source.
 *
 * Phase 2 implementation pulls recent messages from each conversation
 * via [ConversationRepository.getRecentMessages] and filters client-side.
 * Phase 3 will add a Room FTS index for sub-second search across
 * thousands of conversations.
 */
@Singleton
class ConversationSearchTool @Inject constructor(
    private val conversationRepository: ConversationRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "search_conversations",
        description = "Search the user's past conversations for a keyword. Returns matching " +
            "messages with their conversation id and timestamp. Use this when the user asks " +
            "'didn't we talk about X before?'.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "Keyword to search for (case-insensitive).",
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Maximum number of matching messages to return. Defaults to 5.",
                required = false,
            ),
        ),
        category = "information",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val query = arguments["query"]?.extractString()
            ?: return ToolResult.error("missing required parameter: query")
        val limit = arguments["limit"]?.extractString()?.toIntOrNull() ?: 5
        val needle = query.lowercase()

        // Phase 2: linear scan over recent messages of each conversation.
        // Phase 3: FTS4 virtual table on a messages_fts mirror.
        val matches = mutableListOf<String>()
        val convos = conversationRepository.observeConversations().firstSnapshot()
        for (c in convos) {
            val recent = conversationRepository.getRecentMessages(c.id, limit = 50)
            for (m in recent) {
                if (m.content.lowercase().contains(needle)) {
                    matches.add("[conv=${c.id.take(8)} role=${m.role}] ${m.content.take(200)}")
                    if (matches.size >= limit) break
                }
            }
            if (matches.size >= limit) break
        }
        val output = if (matches.isEmpty()) {
            "no matches found for \"$query\""
        } else {
            matches.joinToString("\n")
        }
        return ToolResult.ok(output = output, executionMs = System.currentTimeMillis() - start)
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}

/** Helper: take the first emission of a Flow as a suspending snapshot. */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstSnapshot(): T =
    first()
