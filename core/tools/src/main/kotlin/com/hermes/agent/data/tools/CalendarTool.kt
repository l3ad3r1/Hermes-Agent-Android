package com.hermes.agent.data.tools

import com.hermes.agent.domain.calendar.CalendarEventGateway
import com.hermes.agent.domain.calendar.CalendarEventRequest
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Insert a calendar event into the user's local calendar.
 */
@Singleton
class CalendarTool @Inject constructor(
    private val calendarGateway: CalendarEventGateway,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "calendar_add_event",
        description = "Add an event to the user's local calendar. The user will see the event " +
            "in their preferred calendar app.",
        parameters = listOf(
            ToolParameter(
                name = "title",
                type = ToolParameterType.STRING,
                description = "Event title.",
            ),
            ToolParameter(
                name = "start_iso",
                type = ToolParameterType.STRING,
                description = "Start time in ISO-8601 format, e.g. '2026-06-21T14:30:00'.",
            ),
            ToolParameter(
                name = "duration_minutes",
                type = ToolParameterType.INTEGER,
                description = "Event duration in minutes. Defaults to 60.",
                required = false,
            ),
            ToolParameter(
                name = "location",
                type = ToolParameterType.STRING,
                description = "Optional location string.",
                required = false,
            ),
        ),
        category = "productivity",
        capabilities = setOf("calendar", "productivity"),
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val title = arguments["title"]?.extractString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.error("missing required parameter: title")
        val startIso = arguments["start_iso"]?.extractString()
            ?: return ToolResult.error("missing required parameter: start_iso")
        val duration = arguments["duration_minutes"]?.extractString()?.toIntOrNull() ?: 60
        if (duration !in 1..10_080) {
            return ToolResult.error("duration_minutes must be between 1 and 10080")
        }
        val location = arguments["location"]?.extractString()?.trim()?.takeIf { it.isNotEmpty() }

        val zoneId = ZoneId.systemDefault()
        val startInstant = runCatching { OffsetDateTime.parse(startIso).toInstant() }
            .recoverCatching { LocalDateTime.parse(startIso).atZone(zoneId).toInstant() }
            .getOrElse { return ToolResult.error("start_iso is not valid ISO-8601: $startIso") }
        val endInstant = startInstant.plusSeconds(duration * 60L)

        return calendarGateway.createEvent(
            CalendarEventRequest(
                title = title,
                start = startInstant,
                end = endInstant,
                timeZoneId = zoneId.id,
                location = location,
            ),
        ).fold(
            onSuccess = { created ->
                ToolResult.ok(
                    output = "Event created in ${created.calendarName} " +
                        "(id=${created.id}): $title, $startIso, ${duration}m",
                    executionMs = System.currentTimeMillis() - start,
                )
            },
            onFailure = { error ->
                ToolResult.error(
                    message = "Failed to create calendar event: " +
                        (error.message ?: error.javaClass.simpleName),
                    executionMs = System.currentTimeMillis() - start,
                )
            },
        )
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CalendarToolModule {
    @Binds
    @IntoSet
    abstract fun bindCalendarTool(tool: CalendarTool): Tool
}
