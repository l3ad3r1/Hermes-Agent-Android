package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hermes.agent.domain.model.ScheduledTask
import com.hermes.agent.domain.model.TaskSchedule

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val label: String,
    val prompt: String,
    val scheduleName: String,
    val isEnabled: Boolean,
    val lastRunAt: Long?,
    val lastResult: String?,
    val createdAt: Long,
) {
    fun toDomain() = ScheduledTask(
        id = id,
        label = label,
        prompt = prompt,
        schedule = runCatching { TaskSchedule.valueOf(scheduleName) }
            .getOrDefault(TaskSchedule.DAILY_MORNING),
        isEnabled = isEnabled,
        lastRunAt = lastRunAt,
        lastResult = lastResult,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(task: ScheduledTask) = ScheduledTaskEntity(
            id = task.id,
            label = task.label,
            prompt = task.prompt,
            scheduleName = task.schedule.name,
            isEnabled = task.isEnabled,
            lastRunAt = task.lastRunAt,
            lastResult = task.lastResult,
            createdAt = task.createdAt,
        )
    }
}
