package com.hermes.agent.domain.model

data class ScheduledTask(
    val id: String,
    val label: String,
    val prompt: String,
    /** Standard 5-field cron expression, e.g. "0 8 * * *" (daily at 8 am).
     *  Mirrors NousResearch/hermes-agent cron job format. */
    val cronExpression: String,
    val isEnabled: Boolean = true,
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Common cron expression presets for the UI picker. */
object CronPresets {
    const val HOURLY        = "0 * * * *"
    const val DAILY_MORNING = "0 8 * * *"
    const val DAILY_EVENING = "0 18 * * *"
    const val EVENING_WIND_DOWN = "0 21 * * *"
    const val WEEKDAYS      = "0 8 * * 1-5"
    const val WEEKLY        = "0 8 * * 1"

    val ALL = listOf(
        "Every hour"         to HOURLY,
        "Daily at 8 am"      to DAILY_MORNING,
        "Daily at 6 pm"      to DAILY_EVENING,
        "Evening Wind-Down (9 pm)" to EVENING_WIND_DOWN,
        "Weekdays at 8 am"   to WEEKDAYS,
        "Weekly (Mon 8 am)"  to WEEKLY,
    )

    fun labelFor(expr: String): String {
        ALL.firstOrNull { it.second == expr }?.let { return it.first }
        // "M H * * *" from the time picker renders as a human time.
        DAILY_AT.matchEntire(expr.trim())?.let { m ->
            val minute = m.groupValues[1].toInt()
            val hour = m.groupValues[2].toInt()
            if (minute in 0..59 && hour in 0..23) {
                val h12 = when (hour % 12) { 0 -> 12; else -> hour % 12 }
                val ampm = if (hour < 12) "am" else "pm"
                return "Daily at $h12:${minute.toString().padStart(2, '0')} $ampm"
            }
        }
        return expr
    }

    private val DAILY_AT = Regex("""^(\d{1,2}) (\d{1,2}) \* \* \*$""")
}
