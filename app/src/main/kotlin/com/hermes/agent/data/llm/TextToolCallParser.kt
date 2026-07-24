package com.hermes.agent.data.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/** Parses the textual tool envelope emitted by local and Hermes/Nous-style models. */
internal fun extractTextToolCalls(content: String, json: Json): Pair<String, List<ToolCall>> {
    if (content.isBlank() || !content.contains("<tool", ignoreCase = true)) {
        return content to emptyList()
    }
    val calls = mutableListOf<ToolCall>()
    TOOL_CALL_TAG.findAll(content).forEach { match ->
        val element = runCatching {
            json.parseToJsonElement(match.groupValues[1].trim())
        }.getOrNull() ?: return@forEach
        val objects = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        objects.forEach { obj ->
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val arguments: Map<String, JsonElement> = when (val value = obj["arguments"]) {
                is JsonObject -> value
                is JsonPrimitive -> runCatching {
                    json.parseToJsonElement(value.content).jsonObject
                }.getOrNull() ?: emptyMap()
                else -> emptyMap()
            }
            calls += ToolCall(
                id = "text_call_${TOOL_CALL_ID.incrementAndGet()}",
                name = name,
                arguments = arguments,
            )
        }
    }
    if (calls.isEmpty()) return content to emptyList()
    return TOOL_CALL_TAG.replace(content, "").trim() to calls
}

private val TOOL_CALL_TAG = Regex(
    "<(?:tool_call|toolcall)>(.*?)</(?:tool_call|toolcall)>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val TOOL_CALL_ID = AtomicLong(0)
