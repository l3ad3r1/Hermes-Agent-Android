package com.hermes.agent.domain.model

data class ScheduledTask(
    val id: String,
    val label: String,
    val prompt: String,
    val schedule: TaskSchedule,
    val isEnabled: Boolean = true,
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class TaskSchedule(val label: String, val intervalMinutes: Long) {
    HOURLY("Every hour", 60L),
    DAILY_MORNING("Daily at 8 am", 24 * 60L),
    DAILY_EVENING("Daily at 6 pm", 24 * 60L),
    WEEKDAYS("Every weekday", 24 * 60L),
    WEEKLY("Weekly", 7 * 24 * 60L),
}
