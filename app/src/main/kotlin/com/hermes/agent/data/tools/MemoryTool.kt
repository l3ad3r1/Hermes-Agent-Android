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
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM-callable memory tool. Mirrors the `memory` tool from
 * NousResearch/hermes-agent — the agent stores facts between sessions,
 * searches them semantically, or lists everything it knows.
 *
 * Actions:
 *   add    — persist a fact to long-term memory
 *   search — semantic search (text match in Phase 1; vector in Phase 3.x)
 *   list   — dump all memories (token-heavy; prefer search)
 *   delete — remove a memory by id
 */
@Singleton
class MemoryTool @Inject constructor(
    private val memoryRepository: MemoryRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "memory",
        description = "Persistent long-term memory. " +
            "Use action='add' to store an important fact, preference, or piece of context about the user. " +
            "Use action='search' with a query to recall relevant memories. " +
            "Use action='list' to see all stored memories. " +
            "Use action='delete' with a memory id to remove a specific entry.",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "'add', 'search', 'list', or 'delete'.",
                enumValues = listOf("add", "search", "list", "delete"),
            ),
            ToolParameter(
                name = "content",
                type = ToolParameterType.STRING,
                description = "The fact to store (required for action='add'). Write in first-person: 'User prefers dark mode'.",
                required = false,
            ),
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "Search query (required for action='search').",
                required = false,
            ),
            ToolParameter(
                name = "id",
                type = ToolParameterType.STRING,
                description = "Memory id to delete (required for action='delete').",
                required = false,
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Max results for action='search' (default 5).",
                required = false,
            ),
        ),
        category = "productivity",
        maxResultSizeChars = 4096,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.error("missing required parameter: action")

        return when (action) {
            "add" -> {
                val content = (arguments["content"] as? JsonPrimitive)?.contentOrNull
                    ?: return ToolResult.error("action='add' requires parameter: content")
                val id = memoryRepository.addMemory(content)
                ToolResult.ok("Memory stored (id=$id): $content", System.currentTimeMillis() - start)
            }

            "search" -> {
                val query = (arguments["query"] as? JsonPrimitive)?.contentOrNull
                    ?: return ToolResult.error("action='search' requires parameter: query")
                val limit = (arguments["limit"] as? JsonPrimitive)?.intOrNull ?: 5
                val results = memoryRepository.searchMemories(query, limit)
                if (results.isEmpty()) {
                    ToolResult.ok("No memories found matching '$query'.", System.currentTimeMillis() - start)
                } else {
                    val out = buildString {
                        appendLine("## Memories matching '$query' (${results.size})")
                        results.forEach { m ->
                            appendLine("- [${m.id.take(8)}] ${m.content}")
                        }
                    }
                    ToolResult.ok(out.trim(), System.currentTimeMillis() - start)
                }
            }

            "list" -> {
                val all = memoryRepository.searchMemories("", limit = 100)
                if (all.isEmpty()) {
                    ToolResult.ok("No memories stored yet.", System.currentTimeMillis() - start)
                } else {
                    val out = buildString {
                        appendLine("## All Memories (${all.size})")
                        all.forEach { m -> appendLine("- [${m.id.take(8)}] ${m.content}") }
                    }
                    ToolResult.ok(out.trim(), System.currentTimeMillis() - start)
                }
            }

            "delete" -> {
                val id = (arguments["id"] as? JsonPrimitive)?.contentOrNull
                    ?: return ToolResult.error("action='delete' requires parameter: id")
                memoryRepository.deleteMemory(id)
                ToolResult.ok("Memory $id deleted.", System.currentTimeMillis() - start)
            }

            else -> ToolResult.error("Unknown action '$action'. Use 'add', 'search', 'list', or 'delete'.")
        }
    }
}
