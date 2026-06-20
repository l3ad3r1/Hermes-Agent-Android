package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hermes.agent.domain.model.CronPresets
import com.hermes.agent.domain.model.ScheduledTask

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val label: String,
    val prompt: String,
    /** Legacy field kept for DB compatibility; empty string for new rows. */
    val scheduleName: String = "",
    /** Standard 5-field cron expression added in DB v5. */
    val cronExpression: String = "",
    val isEnabled: Boolean,
    val lastRunAt: Long?,
    val lastResult: String?,
    val createdAt: Long,
) {
    fun toDomain() = ScheduledTask(
        id = id,
        label = label,
        prompt = prompt,
        cronExpression = cronExpression.ifBlank {
            when (scheduleName) {
                "HOURLY"        -> CronPresets.HOURLY
                "DAILY_EVENING" -> CronPresets.DAILY_EVENING
                "WEEKDAYS"      -> CronPresets.WEEKDAYS
                "WEEKLY"        -> CronPresets.WEEKLY
                else            -> CronPresets.DAILY_MORNING
            }
        },
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
            scheduleName = "",
            cronExpression = task.cronExpression,
            isEnabled = task.isEnabled,
            lastRunAt = task.lastRunAt,
            lastResult = task.lastResult,
            createdAt = task.createdAt,
        )
    }
}
