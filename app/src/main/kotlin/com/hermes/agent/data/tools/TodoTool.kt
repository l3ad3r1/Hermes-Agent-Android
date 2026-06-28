package com.hermes.agent.data.tools

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
import java.util.concurrent.CopyOnWriteArrayList
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
 * The store is a process-wide singleton — the Android app drives one active
 * agent session at a time, so a single shared list matches upstream's
 * "one TodoStore per session" semantics closely enough without threading a
 * session id through the tool layer.
 */
@Singleton
class TodoTool @Inject constructor() : Tool {

    /** One item of the plan. `id` is agent-chosen and stable across updates. */
    private data class Item(val id: String, var content: String, var status: String)

    private val items = CopyOnWriteArrayList<Item>()

    override val descriptor = ToolDescriptor(
        name = "todo",
        description = "Maintain a structured task list for the current session. Use it to break a " +
            "complex request into steps, track progress, and stay focused over long conversations. " +
            "Call with a `todos` array to write the list (each item: id, content, status); omit " +
            "`todos` to read the current list. Status is one of: pending, in_progress, completed, " +
            "cancelled. Mark exactly one item in_progress at a time and complete it before starting " +
            "the next. Set merge=true to update items by id and append new ones instead of replacing.",
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
            return ToolResult.ok(render(), System.currentTimeMillis() - start)
        }

        val incoming = runCatching { todosArg.map { it.toItem() } }
            .getOrElse { t ->
                return ToolResult.error(
                    t.message ?: "could not parse todos", System.currentTimeMillis() - start,
                )
            }

        if (merge) mergeItems(incoming) else replaceItems(incoming)

        return ToolResult.ok(render(), System.currentTimeMillis() - start)
    }

    private fun replaceItems(incoming: List<Item>) {
        val deduped = dedupeById(incoming)
        items.clear()
        items.addAll(deduped.take(MAX_ITEMS))
    }

    private fun mergeItems(incoming: List<Item>) {
        for (t in dedupeById(incoming)) {
            val existing = items.firstOrNull { it.id == t.id }
            if (existing != null) {
                existing.content = t.content
                existing.status = t.status
            } else if (items.size < MAX_ITEMS) {
                items.add(t)
            }
        }
    }

    /** Keep the last occurrence of each id, preserving first-seen order. */
    private fun dedupeById(incoming: List<Item>): List<Item> {
        val byId = LinkedHashMap<String, Item>()
        for (item in incoming) byId[item.id] = item
        return byId.values.toList()
    }

    private fun JsonElement.toItem(): Item {
        val obj = this as? JsonObject ?: throw IllegalArgumentException("each todo must be an object")
        val id = obj["id"]?.str()?.trim().orEmpty()
            .ifEmpty { throw IllegalArgumentException("todo item missing 'id'") }
        val content = obj["content"]?.str()?.trim()?.take(MAX_CONTENT_CHARS).orEmpty()
            .ifEmpty { throw IllegalArgumentException("todo item '$id' missing 'content'") }
        val status = obj["status"]?.str()?.trim()?.lowercase()?.takeIf { it in VALID_STATUSES }
            ?: "pending"
        return Item(id, content, status)
    }

    private fun render(): String {
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
        const val MAX_ITEMS = 256
    }
}
