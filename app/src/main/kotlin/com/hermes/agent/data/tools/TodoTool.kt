package com.hermes.agent.data.tools

import com.hermes.agent.data.agent.TodoStore
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory planning / task-tracking list the agent uses to decompose
 * complex requests and keep focus across a long conversation. Ported from
 * hermes-agent's `todo_tool.py`.
 *
 * Behaviour mirrors upstream: a single `todo` tool whose call writes when a
 * `todos` array is supplied and reads (returns the current list) when it is
 * omitted. Every call returns the full list so the model always re-reads its
 * own plan. List order is priority.
 *
 * The list lives in [TodoStore] (a shared singleton) so the chat UI can show
 * the user the agent's live plan as it works.
 */
@Singleton
class TodoTool @Inject constructor(
    private val store: TodoStore,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "todo",
        description = "Maintain a structured task list for the current session. Use it to break a " +
            "complex request into steps, track progress, and stay focused over long conversations. " +
            "Call with a `todos` array to write the list (each item: id, content, status); omit " +
            "`todos` to read the current list. Status is one of: pending, in_progress, completed, " +
            "cancelled. Mark exactly one item in_progress at a time and complete it before starting " +
            "the next. Set merge=true to update items by id and append new ones instead of replacing. " +
            "The user sees this list in the app, so keep it current.",
        parameters = listOf(
            ToolParameter(
                name = "todos",
                type = ToolParameterType.ARRAY,
                description = "Array of todo objects, each {\"id\": string, \"content\": string, " +
                    "\"status\": pending|in_progress|completed|cancelled}. Omit to read the list.",
                required = false,
            ),
            ToolParameter(
                name = "merge",
                type = ToolParameterType.BOOLEAN,
                description = "If true, update existing items by id and append new ones. If false " +
                    "(default), replace the whole list with `todos`.",
                required = false,
            ),
        ),
        category = "productivity",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val todosArg = arguments["todos"] as? JsonArray
        val merge = (arguments["merge"] as? JsonPrimitive)?.booleanOrNull ?: false

        if (todosArg == null) {
            return ToolResult.ok(render(store.snapshot()), System.currentTimeMillis() - start)
        }

        val incoming = runCatching { todosArg.map { it.toItem() } }
            .getOrElse { t ->
                return ToolResult.error(
                    t.message ?: "could not parse todos", System.currentTimeMillis() - start,
                )
            }

        val result = store.write(incoming, merge)
        return ToolResult.ok(render(result), System.currentTimeMillis() - start)
    }

    private fun JsonElement.toItem(): TodoStore.Item {
        val obj = this as? JsonObject ?: throw IllegalArgumentException("each todo must be an object")
        val id = obj["id"]?.str()?.trim().orEmpty()
            .ifEmpty { throw IllegalArgumentException("todo item missing 'id'") }
        val content = obj["content"]?.str()?.trim()?.take(MAX_CONTENT_CHARS).orEmpty()
            .ifEmpty { throw IllegalArgumentException("todo item '$id' missing 'content'") }
        val status = obj["status"]?.str()?.trim()?.lowercase()?.takeIf { it in VALID_STATUSES }
            ?: "pending"
        return TodoStore.Item(id, content, status)
    }

    private fun render(items: List<TodoStore.Item>): String {
        if (items.isEmpty()) return "(todo list is empty)"
        return items.joinToString("\n") { item ->
            val marker = when (item.status) {
                "completed" -> "[x]"
                "in_progress" -> "[>]"
                "cancelled" -> "[-]"
                else -> "[ ]"
            }
            "$marker ${item.id}: ${item.content}"
        }
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    private companion object {
        val VALID_STATUSES = setOf("pending", "in_progress", "completed", "cancelled")
        const val MAX_CONTENT_CHARS = 4000
    }
}
