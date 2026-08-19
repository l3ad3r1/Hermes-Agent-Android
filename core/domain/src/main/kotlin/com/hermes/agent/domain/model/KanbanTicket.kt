package com.hermes.agent.domain.model

/**
 * Kanban ticket — a unit of work the agent (or the user) tracks on the board.
 *
 * Ported and adapted from the standalone "Hermes Android App 2" Kanban prototype,
 * re-homed into the `com.hermes.agent` package and backed by real Room persistence
 * (see [com.hermes.agent.data.local.entity.KanbanTicketEntity]). The original
 * prototype rendered from hard-coded in-memory data; here every ticket is durable
 * and the [com.hermes.agent.service.AgentForegroundService] can claim/execute
 * TODO tickets in the background.
 */
data class KanbanTicket(
    val id: String,
    val title: String,
    val body: String = "",
    val status: KanbanStatus = KanbanStatus.TODO,
    val assignee: String? = null,
    val createdBy: String = "hermes",
    val priority: TicketPriority = TicketPriority.MEDIUM,
    val tags: List<String> = emptyList(),
    val result: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

enum class KanbanStatus {
    TODO,
    IN_PROGRESS,
    REVIEW,
    BLOCKED,
    DONE,
    CANCELLED;

    companion object {
        fun fromName(name: String): KanbanStatus =
            entries.firstOrNull { it.name == name } ?: TODO
    }
}

enum class TicketPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    companion object {
        fun fromName(name: String): TicketPriority =
            entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}
