package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hermes.agent.domain.model.AgentTask
import com.hermes.agent.domain.model.AgentTaskStatus

@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val label: String,
    val prompt: String,
    val statusName: String,
    val result: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
) {
    fun toDomain() = AgentTask(
        id = id,
        label = label,
        prompt = prompt,
        status = AgentTaskStatus.valueOf(statusName),
        result = result,
        createdAt = createdAt,
        startedAt = startedAt,
        completedAt = completedAt,
    )

    companion object {
        fun fromDomain(t: AgentTask) = AgentTaskEntity(
            id = t.id,
            label = t.label,
            prompt = t.prompt,
            statusName = t.status.name,
            result = t.result,
            createdAt = t.createdAt,
            startedAt = t.startedAt,
            completedAt = t.completedAt,
        )
    }
}
