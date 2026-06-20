package com.hermes.agent.data.llm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tool call emitted by the LLM as part of its reply.
 *
 * Mirrors the OpenAI chat-completions `tool_calls` schema:
 *   - [id] is the call id the LLM assigns; the next user turn must echo
 *     it back via a `role=tool` message.
 *   - [name] is the tool descriptor name (matches [com.hermes.agent.domain.tool.ToolDescriptor.name]).
 *   - [arguments] is a JSON object keyed by parameter name.
 *
 * Phase 2 parses these from the LLM response payload; Phase 1's mock
 * provider fabricates them when it wants to demonstrate a function-calling
 * round-trip in the UI.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>,
) {
    /**
     * Convenience: encode the arguments back to a JSON string for
     * inclusion in a `role=tool` reply message.
     */
    fun argumentsJson(): String {
        val obj = JsonObject(arguments)
        return kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), obj)
    }
}

/**
 * Streaming variant of [LlmResponse] that may include tool calls.
 *
 * If [toolCalls] is non-empty, [content] is typically empty or a short
 * "I'll look that up for you." prefix. The orchestrator must execute
 * each tool call, then re-prompt the LLM with the tool results before
 * producing the final user-facing reply.
 */
data class LlmToolResponse(
    val content: String,
    val toolCalls: List<ToolCall>,
    val tokensUsed: Int,
    val model: String,
    val finishReason: String,
)

/** Reasons an LLM stream might terminate. */
sealed class LlmFinishReason {
    /** The model emitted a complete assistant message. */
    object Stop : LlmFinishReason()

    /** The model emitted one or more tool calls and is waiting for results. */
    object ToolCalls : LlmFinishReason()

    /** The model hit the max-token limit. */
    object Length : LlmFinishReason()

    /** Unknown / unspecified. */
    data class Other(val raw: String) : LlmFinishReason()

    companion object {
        fun fromWire(s: String?): LlmFinishReason = when (s?.lowercase()) {
            null, "stop" -> Stop
            "tool_calls", "function_call" -> ToolCalls
            "length" -> Length
            else -> Other(s)
        }
    }
}
