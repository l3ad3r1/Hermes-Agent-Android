package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.model.KanbanTicket
import com.hermes.agent.domain.model.TicketPriority
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "kanban_tickets")
data class KanbanTicketEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val status: String,
    val assignee: String?,
    val createdBy: String,
    val priority: String,
    val tagsJson: String,
    val result: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
) {
    fun toDomain() = KanbanTicket(
        id = id,
        title = title,
        body = body,
        status = KanbanStatus.fromName(status),
        assignee = assignee,
        createdBy = createdBy,
        priority = TicketPriority.fromName(priority),
        tags = runCatching { Json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList()),
        result = result,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )

    companion object {
        fun fromDomain(t: KanbanTicket) = KanbanTicketEntity(
            id = t.id,
            title = t.title,
            body = t.body,
            status = t.status.name,
            assignee = t.assignee,
            createdBy = t.createdBy,
            priority = t.priority.name,
            tagsJson = Json.encodeToString(t.tags),
            result = t.result,
            createdAt = t.createdAt,
            updatedAt = t.updatedAt,
            completedAt = t.completedAt,
        )
    }
}
