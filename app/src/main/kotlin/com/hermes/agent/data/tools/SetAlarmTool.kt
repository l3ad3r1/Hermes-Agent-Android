package com.hermes.agent.data.tools

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import com.sassybutler.alarm.Alarm
import com.sassybutler.alarm.AlarmScheduler
import com.sassybutler.alarm.AlarmStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Set a Sassy Butler alarm (`:feature:butler`) on the user's behalf.
 *
 * Injects Butler's [AlarmScheduler] out of the unified Hilt graph (bound by
 * `com.sassybutler.alarm.di.ButlerModule`).
 *
 * Mirrors what Butler's own `AddAlarmSheet` does, and in the same order: persist the alarm
 * to [AlarmStore] *first*, then schedule it. Persisting matters for two reasons — the alarm
 * appears in Butler's list, and `AlarmReceiver` re-registers stored alarms after a reboot.
 * Scheduling without storing would produce an alarm that vanishes on restart.
 *
 * An empty `days` set means a one-shot alarm at the next occurrence of the time (Butler
 * labels it "Once").
 */
@Singleton
class SetAlarmTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmScheduler: AlarmScheduler,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "set_alarm",
        description = "Set an alarm clock that wakes the user at a given time, using the " +
            "Sassy Butler alarm app. Use this for 'wake me at 7am' or 'set an alarm for 6:30'. " +
            "The alarm fires once, at the next occurrence of that time. This is NOT for " +
            "recurring background tasks — use the 'scheduler' tool for those.",
        parameters = listOf(
            ToolParameter(
                name = "hour",
                type = ToolParameterType.INTEGER,
                description = "Hour of day on a 24-hour clock, 0-23.",
            ),
            ToolParameter(
                name = "minute",
                type = ToolParameterType.INTEGER,
                description = "Minute of the hour, 0-59.",
            ),
            ToolParameter(
                name = "label",
                type = ToolParameterType.STRING,
                description = "Optional short label shown when the alarm fires, e.g. 'Standup'.",
                required = false,
            ),
        ),
        category = "productivity",
        requiresConfirmation = false,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()

        val hour = arguments["hour"]?.extractInt()
            ?: return ToolResult.error("missing or non-numeric parameter: hour")
        val minute = arguments["minute"]?.extractInt()
            ?: return ToolResult.error("missing or non-numeric parameter: minute")

        if (hour !in 0..23) return ToolResult.error("hour must be 0-23, got $hour")
        if (minute !in 0..59) return ToolResult.error("minute must be 0-59, got $minute")

        val label = arguments["label"]?.extractString()?.takeIf { it.isNotBlank() } ?: "Alarm"

        return try {
            val alarm = Alarm(
                // Time-derived so asking for the same time twice UPDATES rather than duplicates,
                // offset out of the UI's id space: Butler's AddAlarmSheet assigns sequential ids
                // via AlarmStore.nextId() (1, 2, 3, ...), and a bare hour*100+minute overlaps that
                // range in the midnight hour ("12:05am" -> 5). AlarmStore.upsert and the
                // PendingIntent request code both key on id, so a collision silently replaces a
                // user's alarm.
                id = AGENT_ALARM_ID_BASE + hour * 100 + minute,
                hour = hour,
                minute = minute,
                label = label,
                enabled = true,
                days = emptySet(),          // one-shot: next occurrence of hour:minute
            )
            AlarmStore.upsert(context, alarm)
            alarmScheduler.schedule(alarm)

            val time = "%02d:%02d".format(hour, minute)
            val exact = canScheduleExact()
            val caveat = if (exact) "" else
                " (exact-alarm permission is not granted, so it may fire a few minutes late)"
            ToolResult.ok("alarm \"$label\" set for $time$caveat")
        } catch (e: Exception) {
            ToolResult.error("could not set alarm: ${e.message}")
        }.copy(executionMs = System.currentTimeMillis() - start)
    }

    /** Android 12+ requires SCHEDULE_EXACT_ALARM; Butler falls back to an inexact alarm without it. */
    private fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    /** Accepts both JSON numbers and quoted strings — models emit either. */
    private fun JsonElement.extractInt(): Int? =
        (this as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private companion object {
        /** Agent alarm ids live at 10000 + hour*100 + minute (10000..12359), disjoint from the UI's sequential ids. */
        const val AGENT_ALARM_ID_BASE = 10_000
    }
}
