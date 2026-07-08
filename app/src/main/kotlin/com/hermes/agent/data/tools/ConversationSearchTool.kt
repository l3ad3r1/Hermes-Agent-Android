package com.hermes.agent.data.tools

import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-session conversation search with LLM summarization.
 *
 * Phase 1: linear scan per conversation (original)
 * Phase 2 (this): global SQL LIKE query via [MessageDao.searchAll] across
 *   ALL conversations, followed by an optional LLM summarization pass that
 *   synthesises the matches into a coherent recall summary.
 *
 * The summarization step converts raw message snippets into a paragraph
 * that reads naturally in the agent's context window — this is the
 * "FTS5 session search with LLM summarization" feature from the plan.
 */
@Singleton
class ConversationSearchTool @Inject constructor(
    private val messageDao: MessageDao,
    private val llmProvider: CloudLlmProvider,
) : Tool {

    private val dateFmt = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

    override val descriptor = ToolDescriptor(
        name = "search_conversations",
        description = "Search the user's entire conversation history for a keyword or topic. " +
            "Returns a concise LLM-summarised recall of matching sessions. " +
            "Use this when the user asks 'didn't we talk about X before?' or 'what did I tell you about Y?'.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "Keyword or topic to search for.",
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Max raw matches to retrieve before summarisation (default 10).",
                required = false,
            ),
        ),
        category = "information",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val query = (arguments["query"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.error("missing required parameter: query")
        val limit = (arguments["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 10

        val matches = messageDao.searchAll(
            com.hermes.agent.util.SqlLike.escape(query),
            limit = limit.coerceIn(1, 30),
        )

        if (matches.isEmpty()) {
            return ToolResult.ok(
                "No past conversations found mentioning \"$query\".",
                System.currentTimeMillis() - start,
            )
        }

        // Build a raw snippet block for LLM summarisation.
        val snippets = matches.joinToString("\n\n") { m ->
            val ts = dateFmt.format(Date(m.timestamp))
            "[${m.role.uppercase()} · $ts] ${m.content.take(250)}"
        }

        // Summarise with LLM if available; otherwise return raw snippets.
        val summary = if (llmProvider.isAvailable()) {
            runCatching {
                llmProvider.complete(
                    listOf(
                        LlmMessage(role = "system", content = SUMMARISE_SYSTEM),
                        LlmMessage(
                            role = "user",
                            content = "Query: \"$query\"\n\nMatching messages:\n$snippets",
                        ),
                    )
                ).content.trim()
            }.getOrNull()
        } else null

        val output = summary ?: "Found ${matches.size} messages mentioning \"$query\":\n\n$snippets"
        return ToolResult.ok(output, System.currentTimeMillis() - start)
    }

    companion object {
        private val SUMMARISE_SYSTEM = """
            You are a memory recall assistant. The user is trying to remember something from a
            past conversation. Given the query and matching message snippets, write a brief
            (2-4 sentences) natural-language recall summary — as if you're saying
            "Yes, we talked about this — here's what was said."
            If the matches are not relevant to the query, say so honestly.
            Do not list every message; synthesise into a readable paragraph.
        """.trimIndent()
    }
}
