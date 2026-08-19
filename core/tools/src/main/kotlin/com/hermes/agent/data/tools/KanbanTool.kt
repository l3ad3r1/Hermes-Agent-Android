package com.hermes.agent.data.tools

import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.model.KanbanTicket
import com.hermes.agent.domain.model.TicketPriority
import com.hermes.agent.domain.repository.KanbanRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Persistent Kanban board tool.
 *
 * Allows the agent to break down complex requests into structured subtasks,
 * track multi-phase project progress, create batch tickets on the board,
 * inspect ticket status, and move tickets through lifecycle stages (TODO -> IN_PROGRESS -> DONE).
 *
 * Tickets created in TODO can also be picked up and executed in the background by
 * the always-on [com.hermes.agent.service.AgentForegroundService].
 */
@Singleton
class KanbanTool @Inject constructor(
    private val repository: KanbanRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "kanban",
        description = "Manage persistent tasks and projects on the Kanban board. Use this to break down " +
            "complex, multi-step requests into structured tickets, track progress, create batches of subtasks, " +
            "and inspect or update ticket status. Actions: " +
            "'create' (single ticket with title, body, priority, tags), " +
            "'create_batch' (break down a project into an array of `tickets`), " +
            "'list' (view all tickets or filter by status: TODO, IN_PROGRESS, REVIEW, BLOCKED, DONE, CANCELLED), " +
            "'get' (read details and result of a ticket by id), " +
            "'move' (change ticket status to TODO, IN_PROGRESS, REVIEW, BLOCKED, DONE, or CANCELLED), " +
            "'delete' (remove ticket by id).",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "The Kanban action: create, create_batch, list, get, move, delete.",
                required = true,
            ),
            ToolParameter(
                name = "title",
                type = ToolParameterType.STRING,
                description = "Title of the ticket (for 'create').",
                required = false,
            ),
            ToolParameter(
                name = "body",
                type = ToolParameterType.STRING,
                description = "Detailed description/instructions or criteria for the ticket.",
                required = false,
            ),
            ToolParameter(
                name = "priority",
                type = ToolParameterType.STRING,
                description = "Ticket priority: LOW, MEDIUM, HIGH, CRITICAL. Default: MEDIUM.",
                required = false,
            ),
            ToolParameter(
                name = "tags",
                type = ToolParameterType.ARRAY,
                description = "Array of string tags categorizing the ticket (e.g. ['frontend', 'api']).",
                required = false,
            ),
            ToolParameter(
                name = "tickets",
                type = ToolParameterType.ARRAY,
                description = "For 'create_batch': Array of ticket objects, each with 'title' (required), " +
                    "'body' (optional), 'priority' (optional: LOW, MEDIUM, HIGH, CRITICAL), 'tags' (optional array).",
                required = false,
            ),
            ToolParameter(
                name = "id",
                type = ToolParameterType.STRING,
                description = "Ticket ID (for get, move, delete).",
                required = false,
            ),
            ToolParameter(
                name = "status",
                type = ToolParameterType.STRING,
                description = "Target status for 'move' or filter for 'list': TODO, IN_PROGRESS, REVIEW, BLOCKED, DONE, CANCELLED.",
                required = false,
            ),
            ToolParameter(
                name = "result",
                type = ToolParameterType.STRING,
                description = "Optional result or completion summary when moving a ticket to DONE.",
                required = false,
            ),
        ),
        category = "productivity",
        capabilities = setOf("common", "productivity"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = arguments["action"]?.str()?.lowercase()?.trim()
            ?: return ToolResult.error("Missing required parameter: 'action'", System.currentTimeMillis() - start)

        return when (action) {
            "create" -> handleCreate(arguments, start)
            "create_batch", "batch_create" -> handleCreateBatch(arguments, start)
            "list" -> handleList(arguments, start)
            "get" -> handleGet(arguments, start)
            "move", "update_status" -> handleMove(arguments, start)
            "delete" -> handleDelete(arguments, start)
            else -> ToolResult.error(
                "Unknown action '$action'. Expected create, create_batch, list, get, move, or delete.",
                System.currentTimeMillis() - start,
            )
        }
    }

    private suspend fun handleCreate(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val title = arguments["title"]?.str()?.trim().orEmpty()
        if (title.isBlank()) {
            return ToolResult.error("Missing required parameter 'title' for ticket creation.", System.currentTimeMillis() - start)
        }
        val body = arguments["body"]?.str()?.trim().orEmpty()
        val priorityStr = arguments["priority"]?.str()?.uppercase()?.trim() ?: "MEDIUM"
        val priority = TicketPriority.fromName(priorityStr)
        val tags = (arguments["tags"] as? JsonArray)?.mapNotNull { it.str()?.trim() } ?: emptyList()

        val ticket = repository.create(
            title = title,
            body = body,
            priority = priority,
            tags = tags,
        )

        return ToolResult.ok(
            "Created Kanban ticket #${ticket.id} [${ticket.priority}]: \"${ticket.title}\"\n" +
                "Status: ${ticket.status}" +
                if (tags.isNotEmpty()) "\nTags: ${tags.joinToString(", ")}" else "",
            System.currentTimeMillis() - start,
        )
    }

    private suspend fun handleCreateBatch(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val ticketsArray = arguments["tickets"] as? JsonArray
            ?: return ToolResult.error("Missing 'tickets' array for create_batch.", System.currentTimeMillis() - start)

        if (ticketsArray.isEmpty()) {
            return ToolResult.error("'tickets' array cannot be empty.", System.currentTimeMillis() - start)
        }

        val createdTickets = mutableListOf<KanbanTicket>()
        for ((index, element) in ticketsArray.withIndex()) {
            val obj = element as? JsonObject
                ?: return ToolResult.error("Item at index $index must be an object with 'title'.", System.currentTimeMillis() - start)
            val title = obj["title"]?.str()?.trim().orEmpty()
            if (title.isBlank()) {
                return ToolResult.error("Item at index $index missing 'title'.", System.currentTimeMillis() - start)
            }
            val body = obj["body"]?.str()?.trim().orEmpty()
            val priorityStr = obj["priority"]?.str()?.uppercase()?.trim() ?: "MEDIUM"
            val priority = TicketPriority.fromName(priorityStr)
            val tags = (obj["tags"] as? JsonArray)?.mapNotNull { it.str()?.trim() } ?: emptyList()

            val ticket = repository.create(
                title = title,
                body = body,
                priority = priority,
                tags = tags,
            )
            createdTickets.add(ticket)
        }

        val formatted = buildString {
            append("Successfully created ${createdTickets.size} Kanban tickets:\n")
            createdTickets.forEachIndexed { i, t ->
                append("${i + 1}. #${t.id} [${t.priority}]: ${t.title} (${t.status})\n")
                if (t.body.isNotBlank()) {
                    append("   Description: ${t.body.take(120)}\n")
                }
            }
        }
        return ToolResult.ok(formatted.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleList(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val filterStatus = arguments["status"]?.str()?.uppercase()?.trim()?.takeIf { it.isNotBlank() }?.let {
            KanbanStatus.fromName(it)
        }

        val allTickets = repository.observe().first()
        val filtered = if (filterStatus != null) {
            allTickets.filter { it.status == filterStatus }
        } else {
            allTickets
        }

        if (filtered.isEmpty()) {
            val statusMsg = if (filterStatus != null) " with status $filterStatus" else ""
            return ToolResult.ok("No Kanban tickets found$statusMsg.", System.currentTimeMillis() - start)
        }

        val grouped = filtered.groupBy { it.status }
        val output = buildString {
            append("Kanban Board (${filtered.size} tickets):\n")
            KanbanStatus.entries.forEach { status ->
                val list = grouped[status] ?: emptyList()
                if (list.isNotEmpty()) {
                    append("\n--- ${status.name} (${list.size}) ---\n")
                    list.forEach { t ->
                        append("• #${t.id} [${t.priority}] ${t.title}")
                        if (t.tags.isNotEmpty()) append(" [${t.tags.joinToString(", ")}]")
                        val res = t.result
                        if (res != null) append("\n  Result: ${res.take(100)}")
                        append("\n")

                    }
                }
            }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleGet(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) {
            return ToolResult.error("Missing required parameter 'id' for get.", System.currentTimeMillis() - start)
        }
        val ticket = repository.get(id)
            ?: return ToolResult.error("Kanban ticket #$id not found.", System.currentTimeMillis() - start)

        val output = buildString {
            append("Ticket #${ticket.id}\n")
            append("Title: ${ticket.title}\n")
            append("Status: ${ticket.status}\n")
            append("Priority: ${ticket.priority}\n")
            append("Created By: ${ticket.createdBy}\n")
            if (ticket.assignee != null) append("Assignee: ${ticket.assignee}\n")
            if (ticket.tags.isNotEmpty()) append("Tags: ${ticket.tags.joinToString(", ")}\n")
            if (ticket.body.isNotBlank()) append("\nDescription:\n${ticket.body}\n")
            if (ticket.result != null) append("\nExecution Result:\n${ticket.result}\n")
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleMove(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) {
            return ToolResult.error("Missing required parameter 'id' for move.", System.currentTimeMillis() - start)
        }
        val statusStr = arguments["status"]?.str()?.uppercase()?.trim().orEmpty()
        if (statusStr.isBlank()) {
            return ToolResult.error("Missing required parameter 'status' for move.", System.currentTimeMillis() - start)
        }
        val targetStatus = KanbanStatus.fromName(statusStr)
        val result = arguments["result"]?.str()

        val current = repository.get(id)
            ?: return ToolResult.error("Kanban ticket #$id not found.", System.currentTimeMillis() - start)

        if (targetStatus == KanbanStatus.DONE) {
            repository.complete(id, result ?: current.result)
        } else {
            repository.moveTo(id, targetStatus)
        }

        return ToolResult.ok(
            "Moved ticket #${id} (\"${current.title}\") from ${current.status} to $targetStatus.",
            System.currentTimeMillis() - start,
        )
    }

    private suspend fun handleDelete(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) {
            return ToolResult.error("Missing required parameter 'id' for delete.", System.currentTimeMillis() - start)
        }
        val current = repository.get(id)
            ?: return ToolResult.error("Kanban ticket #$id not found.", System.currentTimeMillis() - start)

        repository.delete(id)
        return ToolResult.ok("Deleted Kanban ticket #${id} (\"${current.title}\").", System.currentTimeMillis() - start)
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}

@Module
@InstallIn(SingletonComponent::class)
abstract class KanbanToolModule {
    @Binds
    @IntoSet
    abstract fun bindKanbanTool(tool: KanbanTool): Tool
}
