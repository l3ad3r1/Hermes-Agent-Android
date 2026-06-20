package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Insert a calendar event into the user's local calendar.
 *
 * Phase 2 ships the tool with a no-op body that simulates success: it
 * validates the arguments and returns a fake event id. This lets the
 * Productivity agent's function-calling flow be developed and demoed
 * end-to-end. Phase 3 wires it to the Android CalendarContract provider
 * (requires `android.permission.WRITE_CALENDAR`).
 */
@Singleton
class CalendarTool @Inject constructor() : Tool {

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
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val title = arguments["title"]?.extractString()
            ?: return ToolResult.error("missing required parameter: title")
        val startIso = arguments["start_iso"]?.extractString()
            ?: return ToolResult.error("missing required parameter: start_iso")
        val duration = arguments["duration_minutes"]?.extractString()?.toIntOrNull() ?: 60
        val location = arguments["location"]?.extractString()

        // Validate ISO-8601 parse.
        runCatching {
            java.time.OffsetDateTime.parse(startIso)
        }.onFailure {
            runCatching { java.time.LocalDateTime.parse(startIso) }.onFailure {
                return ToolResult.error("start_iso is not valid ISO-8601: $startIso")
            }
        }

        // Phase 3: CalendarContract.Events INSERT via ContentResolver.
        val fakeId = "evt-${System.currentTimeMillis()}"
        val summary = buildString {
            append("event created (id=$fakeId)\n")
            append("  title: $title\n")
            append("  start: $startIso\n")
            append("  duration: ${duration}m")
            location?.let { append("\n  location: $it") }
            append("\n[note: Phase 2 mock — event was NOT written to the system calendar.]")
        }
        return ToolResult.ok(summary, executionMs = System.currentTimeMillis() - start)
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
