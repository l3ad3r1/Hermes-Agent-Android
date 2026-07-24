package com.hermes.agent.data.remote

import com.hermes.agent.data.remote.dto.ChatCompletionChunk
import com.hermes.agent.data.remote.dto.ChatCompletionRequest
import com.hermes.agent.data.remote.dto.ChatCompletionResponse
import kotlinx.coroutines.flow.Flow
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url
import retrofit2.http.Streaming

/**
 * OpenAI-compatible chat-completions API.
 *
 * The same Retrofit interface works against any OpenAI-compatible backend
 * (OpenAI, Azure OpenAI, Together, Anyscale, vLLM, llama.cpp server,
 * Ollama, etc.). The base URL and Authorization header are injected by
 * [com.hermes.agent.di.NetworkModule] based on user settings.
 *
 * Phase 3 streaming strategy:
 *   - [streamCompletion] / [streamCompletionRaw] return the raw
 *     [ResponseBody] rather than a Flow. The caller
 *     ([com.hermes.agent.data.llm.CloudLlmProvider]) reads the response
 *     body line-by-line as an SSE event source. This avoids coupling
 *     the network layer to a specific SSE converter library and gives
 *     us full control over backpressure and error handling.
 */
interface OpenAiApi {

    /** OpenAI-compatible model catalogue at `<base-url>/models`. */
    @GET
    suspend fun models(
        @Url url: String,
        @Header("Authorization") authorization: String?,
    ): com.hermes.agent.data.remote.dto.ModelListResponse
    /**
     * Non-streaming chat completion (structured request/response).
     */
    @POST
    suspend fun completion(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest,
    ): ChatCompletionResponse

    /**
     * Non-streaming chat completion accepting a raw JSON body. Used by
     * [com.hermes.agent.data.llm.CloudLlmProvider.completeWithTools] so
     * it can attach the `tools` array without changing the
     * [ChatCompletionRequest] DTO.
     */
    @POST
    suspend fun completionRaw(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: RequestBody,
    ): ResponseBody

    /**
     * Streaming chat completion — typed body. Returns the raw
     * [ResponseBody]; the caller reads it as an SSE event source.
     * Use [streamCompletionRaw] when you need to attach fields not
     * modeled in [ChatCompletionRequest] (e.g. `tools`).
     */
    @Streaming
    @POST
    suspend fun streamCompletion(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest,
    ): ResponseBody

    /**
     * Streaming chat completion — raw body. Returns the raw
     * [ResponseBody] for SSE parsing.
     */
    @Streaming
    @POST
    suspend fun streamCompletionRaw(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: RequestBody,
    ): ResponseBody
}
