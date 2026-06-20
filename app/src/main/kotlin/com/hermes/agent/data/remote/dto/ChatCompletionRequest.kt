package com.hermes.agent.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI-compatible chat-completions request.
 *
 * Spec: https://platform.openai.com/docs/api-reference/chat/create
 *
 * The same DTO works for any OpenAI-compatible backend (OpenAI, Azure
 * OpenAI, Together, Anyscale, vLLM, llama.cpp server, Ollama, etc.) —
 * only the base URL and API key change.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    /** Reasoning effort for o-series / extended-thinking models.
     *  Mirrors hermes-agent VALID_REASONING_EFFORTS. Null = omit from request. */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class ChatMessage(
    val role: String,
    // Nullable: an assistant message that only carries tool_calls has no content.
    val content: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
)

@Serializable
data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDto,
)

@Serializable
data class FunctionCallDto(
    val name: String,
    // OpenAI spec: arguments is a JSON-encoded string, not an object.
    val arguments: String,
)
