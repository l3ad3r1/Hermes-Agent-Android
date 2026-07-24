package com.hermes.agent.data.llm

import com.hermes.agent.data.remote.OpenAiApi
import com.hermes.agent.data.remote.dto.ChatCompletionChunk
import com.hermes.agent.data.remote.dto.ChatCompletionRequest
import com.hermes.agent.data.remote.dto.ChatMessage
import com.hermes.agent.data.remote.dto.ToolCallDto
import com.hermes.agent.data.remote.dto.FunctionCallDto
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud LLM provider — OpenAI-compatible HTTP via Retrofit.
 *
 * Phase 3 additions:
 *   - Real SSE streaming via [OpenAiApi.streamCompletionRaw]. The
 *     provider reads the response body line-by-line as an SSE event
 *     source, parses each `data:` line as a
 *     [com.hermes.agent.data.remote.dto.ChatCompletionChunk], and
 *     emits [LlmStreamChunk.Delta] events. The terminal
 *     `data: [DONE]` sentinel is filtered.
 *   - [streamWithTools] now attaches the `tools` array via a raw JSON
 *     body so function calling works in streaming mode too.
 *
 * Phase 2's "fake streaming" (fetch the full reply, then re-emit word
 * by word) is retained as a fallback for providers that don't support
 * SSE — used automatically when the SSE stream throws.
 *
 * See Section 5.1 ("Cloud LLM Fallback") and Section 4.2 of the plan.
 *
 * Two instances are wired (see [com.hermes.agent.di.LlmModule]): a default
 * PRIMARY one (reads [UserSettings.cloudModel]) used everywhere a bare
 * [CloudLlmProvider] is injected, and an AUX one (reads
 * [UserSettings.auxModel]) qualified `@Named("cloudAux")`. The
 * [HybridLlmRouter] picks between them per request so two cloud models can be
 * used for specialised tasks. Both share the same API key and base URL.
 */
/**
 * Selects which configured model id a [CloudLlmProvider] instance targets.
 * PRIMARY → [UserSettings.cloudModel]; AUX → [UserSettings.auxModel].
 */
enum class CloudModelSource { PRIMARY, AUX }

@Singleton
class CloudLlmProvider @Inject constructor(
    private val api: OpenAiApi,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val json: Json,
    private val modelSource: CloudModelSource,
) : LlmProvider {

    private companion object {
        const val NETWORK_ATTEMPTS = 2
        const val NETWORK_RETRY_DELAY_MS = 350L
    }

    override val name: String =
        if (modelSource == CloudModelSource.AUX) "Hermes-Cloud-Specialised" else "Hermes-Cloud"
    override val isOnDevice: Boolean = false
    override val model: String
        get() = settings.currentBlocking().selectedModel().cleaned()

    /**
     * Model id this instance targets: the primary [UserSettings.cloudModel]
     * or the specialised [UserSettings.auxModel], depending on [modelSource].
     */
    private fun UserSettings.selectedModel(): String =
        if (modelSource == CloudModelSource.AUX) auxModel else cloudModel

    /**
     * Base URL this instance targets. The specialist (AUX) provider may use its
     * own endpoint ([UserSettings.auxBaseUrl]); when that's blank it falls back
     * to the primary provider's endpoint, so the two can share an endpoint or
     * be fully separate.
     */
    private fun UserSettings.activeBaseUrl(): String =
        if (modelSource == CloudModelSource.AUX && auxBaseUrl.isNotBlank()) auxBaseUrl else cloudBaseUrl

    /** API key this instance targets — AUX uses [UserSettings.auxApiKey] when set, else the primary key. */
    private fun UserSettings.activeApiKey(): String =
        if (modelSource == CloudModelSource.AUX && auxApiKey.isNotBlank()) auxApiKey else cloudApiKey

    override suspend fun isAvailable(): Boolean {
        val s = settings.current()
        return s.cloudEnabled && s.activeApiKey().isNotBlank()
    }

    /** Strip control chars / stray whitespace users sometimes paste into Settings. */
    private fun String.cleaned(): String = filter { it.code >= 0x20 }.trim()

    /** Absolute chat-completions URL built from the user's configured base URL. */
    private fun chatUrl(baseUrl: String): String =
        baseUrl.cleaned().trimEnd('/') + "/chat/completions"

    override suspend fun complete(messages: List<LlmMessage>): LlmResponse {
        val s = settings.current()
        require(s.activeApiKey().isNotBlank()) {
            "Cloud LLM is enabled but no API key is set."
        }
        val request = ChatCompletionRequest(
            model = s.selectedModel().cleaned(),
            messages = messages.map { it.toDto() },
            stream = false,
            reasoningEffort = s.reasoningEffort.takeIf { it != "medium" && it.isNotBlank() },
        )
        val auth = "Bearer ${s.activeApiKey().cleaned()}"
        val resp = try {
            retryTransientNetwork {
                api.completion(chatUrl(s.activeBaseUrl()), auth, request)
            }
        } catch (t: Throwable) {
            Timber.tag("CloudLlm").w(t, "Cloud completion failed")
            throw t
        }
        return LlmResponse(
            content = resp.firstContent,
            tokensUsed = resp.usage?.totalTokens ?: (resp.firstContent.length / 4),
            model = resp.model,
            finishReason = resp.choices.firstOrNull()?.finishReason ?: "stop",
        )
    }

    override suspend fun completeWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): LlmToolResponse {
        val s = settings.current()
        require(s.activeApiKey().isNotBlank()) {
            "Cloud LLM is enabled but no API key is set."
        }

        val requestJson = buildString {
            append('{')
            append("\"model\":\"").append(s.selectedModel().cleaned()).append("\",")
            append("\"stream\":false,")
            append("\"messages\":")
            append(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), messages.map { it.toDto() }))
            if (tools.isNotEmpty()) {
                append(",\"tools\":[")
                tools.joinTo(this, separator = ",") { it.toJsonOpenAiString() }
                append(']')
            }
            append('}')
        }

        val auth = "Bearer ${s.activeApiKey().cleaned()}"
        val rawJson: String = try {
            retryTransientNetwork {
                api.completionRaw(
                    chatUrl(s.activeBaseUrl()),
                    auth,
                    requestJson.toRequestBody("application/json; charset=utf-8".toMediaType()),
                ).string()
            }
        } catch (e: retrofit2.HttpException) {
            val errBody = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            Timber.tag("CloudLlm").w(e, "completion-with-tools HTTP %d: %s", e.code(), errBody)
            throw RuntimeException("HTTP ${e.code()}: ${errBody ?: e.message()}", e)
        } catch (t: Throwable) {
            Timber.tag("CloudLlm").w(t, "Cloud completion-with-tools failed")
            throw t
        }

        return parseCompletionResponse(rawJson)
    }

    override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> = flow {
        val s = settings.current()
        if (s.activeApiKey().isBlank()) {
            emit(LlmStreamChunk.Error("cloud API key not set"))
            return@flow
        }

        val request = ChatCompletionRequest(
            model = s.selectedModel().cleaned(),
            messages = messages.map { it.toDto() },
            stream = true,
        )
        val auth = "Bearer ${s.activeApiKey().cleaned()}"

        try {
            val body = api.streamCompletion(chatUrl(s.activeBaseUrl()), auth, request)
            body.use { consumeSseBody(it) { chunk -> emit(LlmStreamChunk.Delta(chunk.deltaContent)) } }
            emit(LlmStreamChunk.Done)
        } catch (t: Throwable) {
            Timber.tag("CloudLlm").w(t, "SSE stream failed; falling back to fake stream")
            // Fallback: fake-stream a non-streaming completion.
            fakeStream(messages).collect { emit(it) }
        }
    }.flowOn(dispatchers.io)

    override fun streamWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): Flow<LlmStreamChunk> = flow {
        val s = settings.current()
        if (s.activeApiKey().isBlank()) {
            emit(LlmStreamChunk.Error("cloud API key not set"))
            return@flow
        }

        val requestJson = buildString {
            append('{')
            append("\"model\":\"").append(s.selectedModel().cleaned()).append("\",")
            append("\"stream\":true,")
            append("\"messages\":")
            append(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), messages.map { it.toDto() }))
            if (tools.isNotEmpty()) {
                append(",\"tools\":[")
                tools.joinTo(this, separator = ",") { it.toJsonOpenAiString() }
                append(']')
            }
            append('}')
        }
        val auth = "Bearer ${s.activeApiKey().cleaned()}"

        try {
            val body = api.streamCompletionRaw(
                chatUrl(s.activeBaseUrl()),
                auth,
                requestJson.toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            body.use { consumeSseBody(it) { chunk -> emit(LlmStreamChunk.Delta(chunk.deltaContent)) } }
            emit(LlmStreamChunk.Done)
        } catch (t: Throwable) {
            Timber.tag("CloudLlm").w(t, "SSE-with-tools stream failed")
            emit(LlmStreamChunk.Error(t.message ?: "SSE stream failed", t))
        }
    }.flowOn(dispatchers.io)

    /**
     * Read an SSE [ResponseBody] line-by-line, parse each `data:` line
     * as a [ChatCompletionChunk], and invoke [onChunk] for each parsed
     * chunk. The `data: [DONE]` sentinel terminates the loop.
     */
    private inline fun consumeSseBody(body: ResponseBody, onChunk: (ChatCompletionChunk) -> Unit) {
        BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                runCatching {
                    json.decodeFromString(ChatCompletionChunk.serializer(), payload)
                }.onSuccess { chunk ->
                    if (chunk.deltaContent.isNotEmpty()) onChunk(chunk)
                }.onFailure { t ->
                    Timber.tag("CloudLlm").w(t, "failed to parse SSE chunk: %s", payload)
                }
            }
        }
    }

    /**
     * Phase 2 fake-streaming fallback. Fetches a non-streaming completion
     * and re-emits it word-by-word. Used when SSE streaming fails or the
     * provider doesn't support SSE.
     */
    private fun fakeStream(messages: List<LlmMessage>): Flow<LlmStreamChunk> = flow {
        val response = try {
            complete(messages)
        } catch (t: Throwable) {
            emit(LlmStreamChunk.Error(t.message ?: "Cloud completion failed", t))
            return@flow
        }
        val tokens = response.content.split(" ").map { if (it.endsWith('\n')) it else "$it " }
        for (tok in tokens) {
            delay(15L)
            emit(LlmStreamChunk.Delta(tok))
        }
        emit(LlmStreamChunk.Done)
    }.flowOn(dispatchers.io)

    /**
     * Retry one transient transport failure. HTTP responses are deliberately
     * excluded: authentication, rate-limit, and server errors must retain
     * their existing handling instead of replaying a request blindly.
     */
    private suspend fun <T> retryTransientNetwork(block: suspend () -> T): T {
        var lastFailure: IOException? = null
        repeat(NETWORK_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (failure: IOException) {
                lastFailure = failure
                Timber.tag("CloudLlm").w(
                    failure,
                    "Cloud transport failed (attempt %d/%d)",
                    attempt + 1,
                    NETWORK_ATTEMPTS,
                )
                if (attempt + 1 < NETWORK_ATTEMPTS) {
                    delay(NETWORK_RETRY_DELAY_MS)
                }
            }
        }
        val failure = requireNotNull(lastFailure)
        throw IOException(failure.toCloudFailureMessage(), failure)
    }

    // --- helpers ---

    private fun LlmMessage.toDto(): ChatMessage = ChatMessage(
        role = role,
        content = content,
        toolCallId = toolCallId,
        toolCalls = toolCalls?.map { tc ->
            ToolCallDto(
                id = tc.id,
                function = FunctionCallDto(name = tc.name, arguments = tc.argumentsJson()),
            )
        },
    )

    private fun parseCompletionResponse(raw: String): LlmToolResponse {
        val element = json.parseToJsonElement(raw).jsonObject
        val model = element["model"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val choice = element["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return LlmToolResponse(
                content = "",
                toolCalls = emptyList(),
                tokensUsed = 0,
                model = model,
                finishReason = "stop",
            )
        val message = choice["message"]?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "stop"
        val tokensUsed = element["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: (content.length / 4)

        val structuredToolCalls = message?.get("tool_calls")?.jsonArray?.mapNotNull { tc ->
            val obj = tc.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val fn = obj["function"]?.jsonObject ?: return@mapNotNull null
            val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val argsRaw = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            val args = runCatching {
                json.parseToJsonElement(argsRaw).jsonObject.mapValues { it.value }
            }.getOrDefault(emptyMap())
            ToolCall(id = id, name = name, arguments = args)
        } ?: emptyList()

        // Fallback: Hermes/Nous-style models emit tool calls as text tags in
        // `content` (e.g. <tool_call>{...}</tool_call>) rather than the OpenAI
        // structured `tool_calls` field. When the structured field is empty,
        // try to recover them from the text so the tool loop still fires.
        val (finalContent, finalToolCalls) = if (structuredToolCalls.isEmpty()) {
            com.hermes.agent.data.llm.extractTextToolCalls(content, json)
        } else {
            content to structuredToolCalls
        }

        return LlmToolResponse(
            content = finalContent,
            toolCalls = finalToolCalls,
            tokensUsed = tokensUsed,
            model = model,
            finishReason = finishReason,
        )
    }

    private fun SettingsRepository.currentBlocking(): com.hermes.agent.data.settings.UserSettings =
        kotlinx.coroutines.runBlocking { current() }

}

/**
 * Extension: serialize a [ToolDescriptor] to the OpenAI `tools` array entry
 * format. Kept here as a private top-level function so the descriptor class
 * stays pure-Kotlin in the domain layer.
 */
private fun ToolDescriptor.toJsonOpenAiString(): String {
    val params = buildString {
        append('{')
        append("\"type\":\"object\",")
        append("\"properties\":{")
        parameters.joinTo(this, ",") { p ->
            val sb = StringBuilder()
            sb.append('"').append(p.name).append("\":{")
            sb.append("\"type\":\"").append(p.type.jsonSchemaType).append('"')
            sb.append(",\"description\":\"").append(p.description.replace("\"", "\\\"")).append('"')
            p.enumValues?.let {
                sb.append(",\"enum\":[")
                it.joinTo(sb, ",") { "\"$it\"" }
                sb.append(']')
            }
            sb.append('}')
        }
        append("},\"required\":[")
        parameters.filter { it.required }.joinTo(this, ",") { "\"${it.name}\"" }
        append("]}")
    }
    return "{\"type\":\"function\",\"function\":{\"name\":\"$name\",\"description\":\"${description.replace("\"", "\\\"")}\",\"parameters\":$params}}"
}
