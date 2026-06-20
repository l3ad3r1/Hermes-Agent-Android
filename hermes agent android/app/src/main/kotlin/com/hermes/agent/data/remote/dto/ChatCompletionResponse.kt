package com.hermes.agent.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Non-streaming chat-completion response. Spec-compliant with the OpenAI API.
 */
@Serializable
data class ChatCompletionResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null,
) {
    @Serializable
    data class Choice(
        val index: Int,
        val message: ChatMessage,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0,
    )

    /** Convenience accessor — first choice's content, or empty string. */
    val firstContent: String get() = choices.firstOrNull()?.message?.content.orEmpty()
}

/**
 * A single Server-Sent-Events chunk in a streaming chat completion.
 *
 * The OpenAI streaming format wraps each chunk in `data: <json>\n\n` and
 * terminates with `data: [DONE]`. Retrofit + kotlinx.serialization handles
 * the SSE framing when [com.hermes.agent.data.remote.OpenAiApi.streamCompletion]
 * is declared as a `Flow<ChatCompletionChunk>`.
 */
@Serializable
data class ChatCompletionChunk(
    val id: String,
    val model: String,
    val choices: List<StreamChoice> = emptyList(),
) {
    @Serializable
    data class StreamChoice(
        val index: Int,
        val delta: Delta,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class Delta(
        val role: String? = null,
        val content: String? = null,
    )

    /** Concatenate all non-null content deltas in this chunk. */
    val deltaContent: String get() = choices.joinToString("") { it.delta.content.orEmpty() }
}
