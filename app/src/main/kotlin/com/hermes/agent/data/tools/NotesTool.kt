package com.hermes.agent.data.tools

import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Create, search, or delete long-term memories on the user's behalf.
 *
 * Wraps [MemoryRepository] behind the Tool contract so the LLM can
 * explicitly remember facts ("remember that I prefer dark mode") or
 * recall them ("what do you know about my preferences?").
 *
 * Phase 1 MemoryRepository supported only manual CRUD; this tool exposes
 * it to the agent layer.
 */
@Singleton
class NotesTool @Inject constructor(
    private val memoryRepository: MemoryRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "notes",
        description = "Manage the user's long-term memory. Use action='remember' to store a " +
            "fact the user explicitly asks you to remember, action='recall' to search past " +
            "memories by keyword, or action='forget' to delete one by id.",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "One of: remember, recall, forget.",
                enumValues = listOf("remember", "recall", "forget"),
            ),
            ToolParameter(
                name = "content",
                type = ToolParameterType.STRING,
                description = "For action='remember': the text to store. " +
                    "For action='recall': the keyword query. " +
                    "For action='forget': the memory id to delete.",
            ),
        ),
        category = "productivity",
        requiresConfirmation = false,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = arguments["action"]?.extractString()
            ?: return ToolResult.error("missing required parameter: action")
        val content = arguments["content"]?.extractString()
            ?: return ToolResult.error("missing required parameter: content")

        return when (action) {
            "remember" -> {
                val id = memoryRepository.addMemory(content)
                ToolResult.ok("memory stored (id=$id)")
            }
            "recall" -> {
                val hits = memoryRepository.searchMemories(content, limit = 5)
                if (hits.isEmpty()) {
                    ToolResult.ok("no matching memories found")
                } else {
                    val formatted = hits.joinToString("\n") { m ->
                        "[${m.id.take(8)}] ${m.content}"
                    }
                    ToolResult.ok(formatted)
                }
            }
            "forget" -> {
                memoryRepository.deleteMemory(content)
                ToolResult.ok("memory deleted")
            }
            else -> ToolResult.error("unknown action: $action")
        }.copy(executionMs = System.currentTimeMillis() - start)
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
