package com.hermes.agent.data.llm

import app.cash.turbine.test
import com.hermes.agent.data.remote.OpenAiApi
import com.hermes.agent.data.remote.dto.ChatCompletionRequest
import com.hermes.agent.data.remote.dto.ChatCompletionResponse
import com.hermes.agent.data.remote.dto.ChatMessage
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.util.DispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CloudLlmProviderTest {

    private lateinit var api: OpenAiApi
    private lateinit var settings: SettingsRepository
    private lateinit var dispatchers: DispatcherProvider
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var provider: CloudLlmProvider

    // Shared scheduler so runTest(testDispatcher) and dispatchers.io use the same virtual clock.
    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = UnconfinedTestDispatcher(testScheduler)

    private val defaultSettings = UserSettings(
        cloudEnabled = true,
        cloudApiKey = "sk-test-key",
        cloudBaseUrl = "https://api.openai.com/v1",
        cloudModel = "gpt-4o-mini",
        reasoningEffort = "medium",
    )

    @Before
    fun setUp() {
        api = mockk()
        settings = mockk()
        dispatchers = mockk()

        io.mockk.every { dispatchers.io } returns testDispatcher
        io.mockk.every { dispatchers.default } returns testDispatcher
        io.mockk.every { dispatchers.main } returns testDispatcher

        provider = CloudLlmProvider(api, settings, dispatchers, json, CloudModelSource.PRIMARY)
    }

    // ── isAvailable ──────────────────────────────────────────────────────────

    @Test
    fun `isAvailable returns true when cloud enabled and key set`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        assertTrue(provider.isAvailable())
    }

    @Test
    fun `isAvailable returns false when cloudEnabled is false`() = runTest {
        coEvery { settings.current() } returns defaultSettings.copy(cloudEnabled = false)
        assertFalse(provider.isAvailable())
    }

    @Test
    fun `isAvailable returns false when API key is blank`() = runTest {
        coEvery { settings.current() } returns defaultSettings.copy(cloudApiKey = "")
        assertFalse(provider.isAvailable())
    }

    @Test
    fun `isAvailable returns false when API key is only whitespace`() = runTest {
        coEvery { settings.current() } returns defaultSettings.copy(cloudApiKey = "   ")
        assertFalse(provider.isAvailable())
    }

    // ── complete ─────────────────────────────────────────────────────────────

    @Test
    fun `complete parses response content and tokens`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        coEvery { api.completion(any(), any(), any()) } returns chatResponse(
            content = "Hello, world!",
            model = "gpt-4o-mini",
            totalTokens = 20,
            finishReason = "stop",
        )

        val result = provider.complete(listOf(LlmMessage("user", "hi")))

        assertEquals("Hello, world!", result.content)
        assertEquals(20, result.tokensUsed)
        assertEquals("gpt-4o-mini", result.model)
        assertEquals("stop", result.finishReason)
    }

    @Test
    fun `complete uses bearer auth and correct URL`() = runTest {
        coEvery { settings.current() } returns defaultSettings.copy(
            cloudBaseUrl = "https://api.openai.com/v1",
            cloudApiKey = "sk-abc123",
        )
        coEvery { api.completion(any(), any(), any()) } returns chatResponse("ok")

        provider.complete(listOf(LlmMessage("user", "test")))

        coVerify {
            api.completion(
                "https://api.openai.com/v1/chat/completions",
                "Bearer sk-abc123",
                any(),
            )
        }
    }

    @Test
    fun `complete trims trailing slash from base URL`() = runTest {
        coEvery { settings.current() } returns defaultSettings.copy(
            cloudBaseUrl = "https://api.openai.com/v1/",
        )
        coEvery { api.completion(any(), any(), any()) } returns chatResponse("ok")

        provider.complete(listOf(LlmMessage("user", "test")))

        coVerify {
            api.completion(
                "https://api.openai.com/v1/chat/completions",
                any(),
                any(),
            )
        }
    }

    @Test(expected = RuntimeException::class)
    fun `complete propagates network exceptions`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        coEvery { api.completion(any(), any(), any()) } throws RuntimeException("timeout")

        provider.complete(listOf(LlmMessage("user", "hi")))
    }

    // ── completeWithTools ─────────────────────────────────────────────────────

    @Test
    fun `completeWithTools parses single tool call`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        val rawJson = """
            {
              "model": "gpt-4o-mini",
              "choices": [{
                "finish_reason": "tool_calls",
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [
                    {
                      "id": "call_abc",
                      "type": "function",
                      "function": {
                        "name": "get_weather",
                        "arguments": "{\"location\":\"London\"}"
                      }
                    }
                  ]
                }
              }],
              "usage": {"total_tokens": 35}
            }
        """.trimIndent()
        coEvery { api.completionRaw(any(), any(), any()) } returns rawJson.toResponseBody("application/json".toMediaType())

        val result = provider.completeWithTools(
            listOf(LlmMessage("user", "Weather in London?")),
            emptyList(),
        )

        assertEquals(1, result.toolCalls.size)
        val call = result.toolCalls[0]
        assertEquals("call_abc", call.id)
        assertEquals("get_weather", call.name)
        assertEquals("London", (call.arguments["location"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(35, result.tokensUsed)
        assertEquals("tool_calls", result.finishReason)
    }

    @Test
    fun `completeWithTools parses multiple tool calls`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        val rawJson = """
            {
              "model": "gpt-4o-mini",
              "choices": [{
                "finish_reason": "tool_calls",
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [
                    {"id":"call_1","type":"function","function":{"name":"search","arguments":"{\"q\":\"kotlin\"}"}},
                    {"id":"call_2","type":"function","function":{"name":"calculator","arguments":"{\"expr\":\"2+2\"}"}}
                  ]
                }
              }],
              "usage": {"total_tokens": 50}
            }
        """.trimIndent()
        coEvery { api.completionRaw(any(), any(), any()) } returns rawJson.toResponseBody("application/json".toMediaType())

        val result = provider.completeWithTools(listOf(LlmMessage("user", "hi")), emptyList())

        assertEquals(2, result.toolCalls.size)
        assertEquals("search", result.toolCalls[0].name)
        assertEquals("calculator", result.toolCalls[1].name)
    }

    @Test
    fun `completeWithTools returns empty toolCalls for plain text response`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        val rawJson = """
            {
              "model": "gpt-4o-mini",
              "choices": [{"finish_reason":"stop","message":{"role":"assistant","content":"just text"}}],
              "usage": {"total_tokens": 10}
            }
        """.trimIndent()
        coEvery { api.completionRaw(any(), any(), any()) } returns rawJson.toResponseBody("application/json".toMediaType())

        val result = provider.completeWithTools(listOf(LlmMessage("user", "hi")), emptyList())

        assertEquals("just text", result.content)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals("stop", result.finishReason)
    }

    // ── stream ────────────────────────────────────────────────────────────────

    @Test
    fun `stream emits delta chunks and Done from SSE body`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        val sse = sseBody("Hello", " world")
        coEvery { api.streamCompletion(any(), any(), any()) } returns sse

        provider.stream(listOf(LlmMessage("user", "hi"))).test {
            assertEquals("Hello", (awaitItem() as LlmStreamChunk.Delta).text)
            assertEquals(" world", (awaitItem() as LlmStreamChunk.Delta).text)
            assertTrue(awaitItem() is LlmStreamChunk.Done)
            awaitComplete()
        }
    }

    @Test
    fun `stream skips empty delta chunks`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        // One chunk with empty content should be filtered; only non-empty emitted.
        val rawSse = "data: {\"id\":\"1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"}}]}\n\n" +
            "data: {\"id\":\"2\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n" +
            "data: [DONE]\n\n"
        coEvery { api.streamCompletion(any(), any(), any()) } returns rawSse.toResponseBody("text/event-stream".toMediaType())

        provider.stream(listOf(LlmMessage("user", "hi"))).test {
            val item = awaitItem()
            assertTrue(item is LlmStreamChunk.Delta)
            assertEquals("hi", (item as LlmStreamChunk.Delta).text)
            assertTrue(awaitItem() is LlmStreamChunk.Done)
            awaitComplete()
        }
    }

    @Test
    fun `stream falls back to fake-stream when SSE throws`() = runTest(testDispatcher) {
        coEvery { settings.current() } returns defaultSettings
        coEvery { api.streamCompletion(any(), any(), any()) } throws RuntimeException("network error")
        // Fake stream calls complete() internally.
        coEvery { api.completion(any(), any(), any()) } returns chatResponse("fallback text")

        provider.stream(listOf(LlmMessage("user", "hi"))).test {
            // Fake stream emits word-by-word then Done.
            val items = mutableListOf<LlmStreamChunk>()
            while (true) {
                val item = awaitItem()
                items += item
                if (item is LlmStreamChunk.Done) break
            }
            awaitComplete()
            val text = items.filterIsInstance<LlmStreamChunk.Delta>().joinToString("") { it.text }
            assertTrue("fallback text" in text)
        }
    }

    @Test
    fun `stream emits Error when API key is blank`() = runTest {
        coEvery { settings.current() } returns defaultSettings.copy(cloudApiKey = "")

        provider.stream(listOf(LlmMessage("user", "hi"))).test {
            val item = awaitItem()
            assertTrue(item is LlmStreamChunk.Error)
            assertTrue((item as LlmStreamChunk.Error).message.contains("API key", ignoreCase = true))
            awaitComplete()
        }
    }

    // ── HTTP error codes ──────────────────────────────────────────────────────

    @Test(expected = Exception::class)
    fun `complete throws on HTTP 401`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        coEvery { api.completion(any(), any(), any()) } throws httpException(401)

        provider.complete(listOf(LlmMessage("user", "hi")))
    }

    @Test(expected = Exception::class)
    fun `complete throws on HTTP 429 rate limit`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        coEvery { api.completion(any(), any(), any()) } throws httpException(429)

        provider.complete(listOf(LlmMessage("user", "hi")))
    }

    @Test(expected = Exception::class)
    fun `complete throws on HTTP 500 server error`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        coEvery { api.completion(any(), any(), any()) } throws httpException(500)

        provider.complete(listOf(LlmMessage("user", "hi")))
    }

    @Test(expected = Exception::class)
    fun `completeWithTools wraps HTTP 401 as RuntimeException`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        coEvery { api.completionRaw(any(), any(), any()) } throws httpException(401)

        provider.completeWithTools(listOf(LlmMessage("user", "hi")), emptyList())
    }

    @Test(expected = Exception::class)
    fun `completeWithTools wraps HTTP 500 as RuntimeException`() = runTest {
        coEvery { settings.current() } returns defaultSettings
        coEvery { api.completionRaw(any(), any(), any()) } throws httpException(500)

        provider.completeWithTools(listOf(LlmMessage("user", "hi")), emptyList())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun chatResponse(
        content: String,
        model: String = "gpt-4o-mini",
        totalTokens: Int = content.length / 4,
        finishReason: String = "stop",
    ) = ChatCompletionResponse(
        id = "test-id",
        model = model,
        choices = listOf(
            ChatCompletionResponse.Choice(
                index = 0,
                message = ChatMessage(role = "assistant", content = content),
                finishReason = finishReason,
            )
        ),
        usage = ChatCompletionResponse.Usage(totalTokens = totalTokens),
    )

    private fun toolCallJson(
        callId: String,
        toolName: String,
        argsJson: String,
        totalTokens: Int = 30,
    ): ResponseBody = """
        {
          "model": "gpt-4o-mini",
          "choices": [{
            "finish_reason": "tool_calls",
            "message": {
              "role": "assistant",
              "content": null,
              "tool_calls": [
                {
                  "id": "$callId",
                  "type": "function",
                  "function": {
                    "name": "$toolName",
                    "arguments": ${org.json.JSONObject.quote(argsJson)}
                  }
                }
              ]
            }
          }],
          "usage": {"total_tokens": $totalTokens}
        }
    """.trimIndent().toResponseBody("application/json".toMediaType())

    private fun sseBody(vararg tokens: String): ResponseBody {
        val sb = StringBuilder()
        tokens.forEach { tok ->
            val escaped = tok.replace("\"", "\\\"")
            sb.append("data: {\"id\":\"1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$escaped\"}}]}\n\n")
        }
        sb.append("data: [DONE]\n\n")
        return sb.toString().toResponseBody("text/event-stream".toMediaType())
    }

    private fun httpException(code: Int): HttpException {
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://api.openai.com/v1/chat/completions").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
        return HttpException(Response.error<Any>("{}".toResponseBody("application/json".toMediaType()), raw))
    }
}
