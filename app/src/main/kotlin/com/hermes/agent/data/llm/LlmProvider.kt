package com.hermes.agent.data.llm

import com.hermes.agent.domain.tool.ToolDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * A single message exchanged with an LLM provider.
 *
 * Mirrors the OpenAI chat-completions message format so the same struct
 * can be sent to either a local or cloud backend without translation.
 *
 * For tool-call round-trips, set [role] = "tool" and [toolCallId] to
 * the id of the originating tool call. The LLM stitches the result
 * back into its context window and continues generating.
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
 * Streaming chunk emitted by [LlmProvider.stream].
 */
sealed class LlmStreamChunk {
    /** Partial token of the assistant reply. */
    data class Delta(val text: String) : LlmStreamChunk()

    /** The LLM wants to invoke a tool. The orchestrator should execute it
     *  and continue the conversation with a `role=tool` reply. */
    data class ToolCallDelta(val toolCall: ToolCall) : LlmStreamChunk()

    /** Stream finished normally. */
    object Done : LlmStreamChunk()

    /** Stream finished with an error. */
    data class Error(val message: String) : LlmStreamChunk()
}

/**
 * Contract every LLM backend must satisfy.
 *
 * Two concrete implementations ship in Phase 2:
 *   - [OnDeviceLlmProvider]  — returns canned responses (mocks MLC-LLM / NPU).
 *   - [CloudLlmProvider]     — OpenAI-compatible HTTP via Retrofit.
 *
 * Phase 2 adds two new methods to the contract:
 *   - [completeWithTools] for non-streaming function-calling rounds.
 *   - [streamWithTools]   for streaming replies that may interleave
 *     tool calls.
 *
 * The original [complete] and [stream] methods remain for callers (intent
 * classification, title generation) that never need tools.
 */
interface LlmProvider {

    /** Human-readable name for diagnostics and the Settings UI. */
    val name: String

    /** True for on-device providers; false for cloud providers. */
    val isOnDevice: Boolean

    /** Currently-selected model id (e.g. "hermes-3-8b-q4f16" or "gpt-4o-mini"). */
    val model: String

    /**
     * Non-streaming completion. Used by internal calls (intent classification,
     * title generation) that don't need incremental output.
     */
    suspend fun complete(messages: List<LlmMessage>): LlmResponse

    /**
     * Streaming completion. The first chunk is the model's first token; the
     * stream terminates with either [LlmStreamChunk.Done] or [LlmStreamChunk.Error].
     */
    fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk>

    /**
     * Non-streaming completion with tool support. The provider sends the
     * [tools] descriptors to the LLM; the LLM may either reply directly
     * (in which case [LlmToolResponse.toolCalls] is empty) or emit one or
     * more tool calls (in which case [LlmToolResponse.content] is typically
     * empty).
     *
     * Default implementation delegates to [complete] and returns an empty
     * toolCalls list — providers that don't support function calling work
     * transparently.
     */
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

    /**
     * Streaming completion with tool support. Default implementation
     * delegates to [stream] and never emits [LlmStreamChunk.ToolCallDelta].
     */
    fun streamWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): Flow<LlmStreamChunk> = stream(messages)

    /**
     * Cheap availability check. Called by [LlmRouter] before selecting this
     * provider. For the on-device provider this reports whether the (mock)
     * model is loaded; for the cloud provider it reports whether an API key
     * is configured.
     */
    suspend fun isAvailable(): Boolean
}
