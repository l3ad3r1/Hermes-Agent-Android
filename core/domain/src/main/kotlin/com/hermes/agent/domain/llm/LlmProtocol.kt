package com.hermes.agent.domain.llm

import com.hermes.agent.domain.tool.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A tool call emitted by the LLM as part of its reply.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>,
) {
    fun argumentsJson(): String {
        val obj = JsonObject(arguments)
        return kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), obj)
    }
}

/**
 * A single message exchanged with an LLM provider.
 */
data class LlmMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
)

/**
 * A complete (non-streaming) LLM response.
 */
data class LlmResponse(
    val content: String,
    val tokensUsed: Int,
    val model: String,
    val finishReason: String = "stop",
)

/**
 * Streaming variant of [LlmResponse] that may include tool calls.
 */
data class LlmToolResponse(
    val content: String,
    val toolCalls: List<ToolCall>,
    val tokensUsed: Int,
    val model: String,
    val finishReason: String,
)

/**
 * Streaming chunk emitted by [LlmProvider.stream].
 */
sealed class LlmStreamChunk {
    data class Delta(val text: String) : LlmStreamChunk()
    data class ToolCallDelta(val toolCall: ToolCall) : LlmStreamChunk()
    object Done : LlmStreamChunk()
    data class Error(val message: String, val cause: Throwable? = null) : LlmStreamChunk()
}

/**
 * Reasons an LLM stream might terminate.
 */
sealed class LlmFinishReason {
    object Stop : LlmFinishReason()
    object ToolCalls : LlmFinishReason()
    object Length : LlmFinishReason()
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

/**
 * Contract every LLM backend must satisfy.
 */
interface LlmProvider {
    val name: String
    val isOnDevice: Boolean
    val model: String

    suspend fun complete(messages: List<LlmMessage>): LlmResponse
    fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk>

    suspend fun completeWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): LlmToolResponse {
        val r = complete(messages)
        return LlmToolResponse(
            content = r.content,
            toolCalls = emptyList(),
            tokensUsed = r.tokensUsed,
            model = r.model,
            finishReason = r.finishReason,
        )
    }

    fun streamWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): Flow<LlmStreamChunk> = stream(messages)

    suspend fun isAvailable(): Boolean
}
