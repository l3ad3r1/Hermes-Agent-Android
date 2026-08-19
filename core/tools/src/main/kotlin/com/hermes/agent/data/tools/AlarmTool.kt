package com.hermes.agent.data.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.hermes.agent.domain.tool.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import com.hermes.agent.domain.tool.Tool

@Singleton
class AlarmTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : Tool {
    override val descriptor = ToolDescriptor(
        name = "alarm",
        description = "Set alarms and timers, dismiss a ringing alarm, or show the alarm list.",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "Alarm action.", enumValues = listOf("set_alarm", "set_timer", "dismiss_alarm", "show_alarms")),
            ToolParameter("hour", ToolParameterType.INTEGER, "Alarm hour from 0 to 23.", required = false),
            ToolParameter("minute", ToolParameterType.INTEGER, "Alarm minute from 0 to 59.", required = false),
            ToolParameter("duration_seconds", ToolParameterType.INTEGER, "Timer duration from 1 to 86400 seconds.", required = false),
            ToolParameter("message", ToolParameterType.STRING, "Optional alarm or timer label.", required = false),
        ),
        category = "device",
        capabilities = setOf("device:alarm", "device"),
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val action = arguments.string("action") ?: return ToolResult.error("missing required parameter: action")
        val intent = when (action) {
            "set_alarm" -> {
                val hour = arguments.int("hour") ?: return ToolResult.error("hour is required")
                val minute = arguments.int("minute") ?: return ToolResult.error("minute is required")
                if (hour !in 0..23 || minute !in 0..59) return ToolResult.error("invalid alarm time")
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .apply { arguments.string("message")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            }
            "set_timer" -> {
                val seconds = arguments.int("duration_seconds") ?: return ToolResult.error("duration_seconds is required")
                if (seconds !in 1..86_400) return ToolResult.error("duration_seconds must be between 1 and 86400")
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .apply { arguments.string("message")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            }
            "dismiss_alarm" -> Intent(AlarmClock.ACTION_DISMISS_ALARM)
            "show_alarms" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            else -> return ToolResult.error("unknown alarm action: $action")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return launch(intent, "Alarm action completed: $action")
    }

    private fun launch(intent: Intent, message: String): ToolResult = runCatching {
        context.startActivity(intent)
        ToolResult.ok(message)
    }.getOrElse { ToolResult.error("No compatible clock app is available: ${it.message}") }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmToolModule {
    @Binds
    @IntoSet
    abstract fun bindAlarmTool(tool: AlarmTool): Tool
}
