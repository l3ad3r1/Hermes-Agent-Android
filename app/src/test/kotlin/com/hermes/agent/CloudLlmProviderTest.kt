package com.hermes.agent

import app.cash.turbine.test
import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmStreamChunk
import com.hermes.agent.data.remote.OpenAiApi
import com.hermes.agent.data.remote.dto.ChatCompletionChunk
import com.hermes.agent.data.remote.dto.ChatCompletionRequest
import com.hermes.agent.data.remote.dto.ChatCompletionResponse
import com.hermes.agent.data.remote.dto.ChoiceChunk
import com.hermes.agent.data.remote.dto.ChoiceResponse
import com.hermes.agent.data.remote.dto.MessageDto
import com.hermes.agent.data.remote.dto.UsageDto
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.util.DispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
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

class CloudLlmProviderTest {

    private lateinit var api: OpenAiApi
    private lateinit var settings: SettingsRepository
    private lateinit var dispatchers: DispatcherProvider
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var provider: CloudLlmProvider

    @Before
    fun setUp() {
        api = mockk()
        settings = mockk()
        dispatchers = mockk()
        
        val testDispatcher = UnconfinedTestDispatcher()
        every { dispatchers.io } returns testDispatcher
        every { dispatchers.default } returns testDispatcher
        every { dispatchers.main } returns testDispatcher
        every { dispatchers.unconfined } returns testDispatcher

        provider = CloudLlmProvider(api, settings, dispatchers, json)
    }

    @Test
    fun `isAvailable returns true when enabled and key is set`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "test-key"
        )
        assertTrue(provider.isAvailable())
    }

    @Test
    fun `isAvailable returns false when disabled`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = false,
            cloudApiKey = "test-key"
        )
        assertFalse(provider.isAvailable())
    }

    @Test
    fun `isAvailable returns false when key is blank`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "  "
        )
        assertFalse(provider.isAvailable())
    }

    @Test
    fun `complete calls api and parses response`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "test-key",
            cloudBaseUrl = "https://api.openai.com/v1",
            cloudModel = "gpt-4"
        )

        val response = ChatCompletionResponse(
            id = "test-id",
            model = "gpt-4",
            choices = listOf(
                ChoiceResponse(
                    index = 0,
                    message = MessageDto(
                        role = "assistant",
                        content = "Hello world"
                    ),
                    finishReason = "stop"
                )
            ),
            usage = UsageDto(totalTokens = 10, promptTokens = 5, completionTokens = 5)
        )

        coEvery { api.completion(any(), any(), any()) } returns response

        val result = provider.complete(listOf(LlmMessage("user", "hi")))
        
        assertEquals("Hello world", result.content)
        assertEquals(10, result.tokensUsed)
        assertEquals("gpt-4", result.model)
        assertEquals("stop", result.finishReason)
    }

    @Test
    fun `completeWithTools uses raw raw completion and parses tool calls`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "test-key",
            cloudBaseUrl = "https://api.openai.com/v1",
            cloudModel = "gpt-4"
        )

        val jsonResponse = """
            {
              "model": "gpt-4",
              "choices": [
                {
                  "finish_reason": "tool_calls",
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_123",
                        "type": "function",
                        "function": {
                          "name": "get_weather",
                          "arguments": "{\"location\":\"San Francisco\"}"
                        }
                      }
                    ]
                  }
                }
              ],
              "usage": {
                "total_tokens": 42
              }
            }
        """.trimIndent()

        coEvery { api.completionRaw(any(), any(), any()) } returns jsonResponse.toResponseBody("application/json".toMediaType())

        val result = provider.completeWithTools(listOf(LlmMessage("user", "weather?")), emptyList())
        
        assertEquals("", result.content)
        assertEquals(1, result.toolCalls.size)
        assertEquals("call_123", result.toolCalls[0].id)
        assertEquals("get_weather", result.toolCalls[0].name)
        assertEquals(mapOf("location" to kotlinx.serialization.json.JsonPrimitive("San Francisco")), result.toolCalls[0].arguments)
        assertEquals(42, result.tokensUsed)
        assertEquals("tool_calls", result.finishReason)
    }

    @Test
    fun `stream emits chunks from SSE`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "test-key",
            cloudBaseUrl = "https://api.openai.com/v1",
            cloudModel = "gpt-4"
        )

        val sseResponse = "" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n" +
                "data: [DONE]\n\n"

        coEvery { api.streamCompletion(any(), any(), any()) } returns sseResponse.toResponseBody("text/event-stream".toMediaType())

        provider.stream(listOf(LlmMessage("user", "hi"))).test {
            val chunk1 = awaitItem()
            assertTrue(chunk1 is LlmStreamChunk.Delta)
            assertEquals("Hello", (chunk1 as LlmStreamChunk.Delta).text)

            val chunk2 = awaitItem()
            assertTrue(chunk2 is LlmStreamChunk.Delta)
            assertEquals(" world", (chunk2 as LlmStreamChunk.Delta).text)

            val chunk3 = awaitItem()
            assertTrue(chunk3 is LlmStreamChunk.Done)

            awaitComplete()
        }
    }
}
