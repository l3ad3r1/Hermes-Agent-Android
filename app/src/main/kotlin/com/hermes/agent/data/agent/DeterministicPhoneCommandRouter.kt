package com.hermes.agent.data.agent

import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.util.IdGenerator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class DeterministicPhoneCommand(
    val call: ToolCall,
    val role: AgentRole,
)

/** High-confidence phone commands that do not require an LLM. */
@Singleton
class DeterministicPhoneCommandRouter @Inject constructor() {

    fun match(input: String): DeterministicPhoneCommand? {
        val text = input.trim()
        val lower = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        TIMER.matchEntire(lower)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return null
            val seconds = when (match.groupValues[2]) {
                "hour", "hours" -> amount * 3600
                "minute", "minutes" -> amount * 60
                else -> amount
            }
            return command("alarm", AgentRole.DEVICE_CONTROL,
                "action" to "set_timer", "duration_seconds" to seconds)
        }

        ALARM.matchEntire(lower)?.let { match ->
            val hourRaw = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull().takeIf { match.groupValues[2].isNotEmpty() } ?: 0
            val suffix = match.groupValues[3]
            val hour = when {
                suffix == "pm" && hourRaw < 12 -> hourRaw + 12
                suffix == "am" && hourRaw == 12 -> 0
                else -> hourRaw
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            return command("alarm", AgentRole.DEVICE_CONTROL,
                "action" to "set_alarm", "hour" to hour, "minute" to minute)
        }

        FLASHLIGHT.matchEntire(lower)?.let { match ->
            return command("device_control", AgentRole.DEVICE_CONTROL,
                "action" to "flashlight", "enabled" to (match.groupValues[1] == "on"))
        }
        DND.matchEntire(lower)?.let { match ->
            return command("device_control", AgentRole.DEVICE_CONTROL,
                "action" to "set_dnd", "enabled" to (match.groupValues[1] == "on"))
        }
        VOLUME.matchEntire(lower)?.let { match ->
            return command("device_control", AgentRole.DEVICE_CONTROL,
                "action" to "set_volume", "stream" to match.groupValues[1].ifBlank { "music" },
                "level" to (match.groupValues[2].toIntOrNull() ?: return null))
        }
        RINGER.matchEntire(lower)?.let { match ->
            return command("device_control", AgentRole.DEVICE_CONTROL,
                "action" to "set_ringer_mode", "mode" to match.groupValues[1])
        }

        NAVIGATE.matchEntire(text)?.let { match ->
            val destination = match.groupValues[1].trim().takeIf { it.isNotEmpty() } ?: return null
            return command("navigation", AgentRole.DEVICE_CONTROL,
                "action" to "navigate", "query" to destination)
        }

        when (lower) {
            "play music", "pause music", "play or pause music" ->
                return command("media_control", AgentRole.DEVICE_CONTROL, "action" to "play_pause")
            "next song", "next track", "skip song" ->
                return command("media_control", AgentRole.DEVICE_CONTROL, "action" to "next")
            "previous song", "previous track" ->
                return command("media_control", AgentRole.DEVICE_CONTROL, "action" to "previous")
        }
        PLAY_SEARCH.matchEntire(text)?.let { match ->
            return command("media_control", AgentRole.DEVICE_CONTROL,
                "action" to "play_search", "query" to match.groupValues[1].trim())
        }

        DIAL.matchEntire(lower)?.let { match ->
            return command("communication", AgentRole.PRODUCTIVITY,
                "action" to "dial", "recipient" to match.groupValues[1])
        }
        CONTACT.matchEntire(text)?.let { match ->
            return command("contact_lookup", AgentRole.PRODUCTIVITY,
                "query" to match.groupValues[1].trim())
        }
        return null
    }

    private fun command(tool: String, role: AgentRole, vararg arguments: Pair<String, Any>): DeterministicPhoneCommand {
        val json = arguments.associate { (key, value) ->
            key to when (value) {
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            } as JsonElement
        }
        return DeterministicPhoneCommand(ToolCall(IdGenerator.newId(), tool, json), role)
    }

    companion object {
        private val TIMER = Regex("(?:set|start)(?: a)? timer(?: for)? (\\d+) (seconds?|minutes?|hours?)", RegexOption.IGNORE_CASE)
        private val ALARM = Regex("(?:set|create)(?: an?)? alarm(?: for| at)? (\\d{1,2})(?::(\\d{2}))? ?(am|pm)?", RegexOption.IGNORE_CASE)
        private val FLASHLIGHT = Regex("turn (on|off) (?:the )?(?:flashlight|torch)", RegexOption.IGNORE_CASE)
        private val DND = Regex("turn (on|off) (?:do not disturb|dnd)", RegexOption.IGNORE_CASE)
        private val VOLUME = Regex("set (?:the )?(?:(music|ring|notification|alarm|system) )?volume to (\\d+)", RegexOption.IGNORE_CASE)
        private val RINGER = Regex("set (?:the )?(?:phone|ringer)(?: mode)? to (normal|vibrate|silent)", RegexOption.IGNORE_CASE)
        private val NAVIGATE = Regex("(?:navigate|give me directions|take me) to (.+)", RegexOption.IGNORE_CASE)
        private val PLAY_SEARCH = Regex("play (.+)", RegexOption.IGNORE_CASE)
        private val DIAL = Regex("(?:call|dial) ([+0-9][0-9 ()-]{4,})", RegexOption.IGNORE_CASE)
        private val CONTACT = Regex("(?:find|search for|look up) contact (.+)", RegexOption.IGNORE_CASE)
    }
}
