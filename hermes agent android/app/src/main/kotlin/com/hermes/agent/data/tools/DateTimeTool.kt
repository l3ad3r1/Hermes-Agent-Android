package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Returns the current date and/or time, optionally in a specific timezone
 * and format. The simplest and most-frequently-invoked tool — and the
 * canonical example for how a Hermes tool is structured.
 */
@Singleton
class DateTimeTool @Inject constructor() : Tool {

    override val descriptor = ToolDescriptor(
        name = "get_current_datetime",
        description = "Get the current date, time, or both, in the device's locale. " +
            "Useful for answering questions like 'what day is it?' or 'what time is it in Tokyo?'.",
        parameters = listOf(
            ToolParameter(
                name = "component",
                type = ToolParameterType.STRING,
                description = "Which component to return.",
                required = false,
                enumValues = listOf("date", "time", "datetime"),
            ),
            ToolParameter(
                name = "timezone",
                type = ToolParameterType.STRING,
                description = "Optional IANA timezone ID (e.g. 'Asia/Tokyo'). " +
                    "Defaults to the device timezone.",
                required = false,
            ),
        ),
        category = "information",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val component = arguments["component"]?.extractString() ?: "datetime"
        val tzId = arguments["timezone"]?.extractString()

        val tz = runCatching {
            tzId?.let { java.time.ZoneId.of(it) }
        }.getOrNull() ?: java.time.ZoneId.systemDefault()

        val now = java.time.ZonedDateTime.now(tz)
        val formatted = when (component.lowercase()) {
            "date" -> now.toLocalDate().toString()
            "time" -> now.toLocalTime().toString().take(8)
            else -> now.toString()
        }
        return ToolResult.ok(
            output = formatted,
            executionMs = System.currentTimeMillis() - start,
        )
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
