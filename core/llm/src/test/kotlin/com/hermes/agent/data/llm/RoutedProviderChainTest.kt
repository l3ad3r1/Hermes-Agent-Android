package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RoutedProviderChainTest {

    @Test
    fun `completion advances when a cloud provider times out`() = runTest {
        val stalled = provider("Stalled cloud", "stalled-model")
        val next = provider("Next cloud", "next-model")
        val messages = listOf(LlmMessage("user", "hello"))
        val expected = LlmResponse("ok", 1, "next-model")
        coEvery { stalled.complete(messages) } coAnswers {
            delay(Long.MAX_VALUE)
            expected
        }
        coEvery { next.complete(messages) } returns expected

        val result = RoutedProviderChain(listOf(stalled, next)).complete(messages)

        assertEquals(expected, result)
        coVerify(exactly = 1) { next.complete(messages) }
    }

    @Test
    fun `rate-limited provider falls through to the next routed provider`() = runTest {
        val first = provider("Groq", "kimi")
        val second = provider("OpenRouter", "nemotron")
        val expected = LlmResponse("ok", 1, "nemotron")
        coEvery { first.complete(any()) } throws httpError(429)
        coEvery { second.complete(any()) } returns expected
        val chain = RoutedProviderChain(listOf(first, second))

        val result = chain.complete(listOf(LlmMessage("user", "analyze this")))

        assertSame(expected, result)
        assertEquals("OpenRouter", chain.name)
    }

    @Test
    fun `paid model without credits falls through to the next routed provider`() = runTest {
        val first = provider("Kilo", "google/gemma-4-31b-it")
        val second = provider("Local", "qwen-local")
        val expected = LlmResponse("offline result", 1, "qwen-local")
        coEvery { first.completeWithTools(any(), any()) } throws RuntimeException(
            "HTTP 402: {\"error_type\":\"usage_limit_exceeded\"}",
            httpError(402),
        )
        coEvery { second.completeWithTools(any(), any()) } returns LlmToolResponse(
            content = expected.content,
            toolCalls = emptyList(),
            tokensUsed = expected.tokensUsed,
            model = expected.model,
            finishReason = "stop",
        )
        val chain = RoutedProviderChain(listOf(first, second))

        val result = chain.completeWithTools(
            listOf(LlmMessage("user", "Create a calendar event")),
            emptyList(),
        )

        assertEquals(expected.content, result.content)
        assertEquals("Local", chain.name)
        coVerify(exactly = 1) { second.completeWithTools(any(), any()) }
    }

    @Test
    fun `provider-specific model unavailable bad request falls through`() = runTest {
        val first = provider("LLM7", "stale-model")
        val second = provider("Mistral", "working-model")
        val expected = LlmResponse("ok", 1, "working-model")
        coEvery { first.complete(any()) } throws RuntimeException(
            "HTTP 400: {\"code\":\"model_unavailable\"}",
            httpError(400),
        )
        coEvery { second.complete(any()) } returns expected

        val result = RoutedProviderChain(listOf(first, second)).complete(
            listOf(LlmMessage("user", "hello")),
        )

        assertSame(expected, result)
    }

    @Test
    fun `provider-specific thinking history requirement falls through`() = runTest {
        val first = provider("OpenCode Zen", "thinking-model")
        val second = provider("OpenCode Go", "tool-model")
        val expected = LlmToolResponse(
            content = "",
            toolCalls = emptyList(),
            tokensUsed = 1,
            model = "tool-model",
            finishReason = "stop",
        )
        coEvery { first.completeWithTools(any(), any()) } throws RuntimeException(
            "HTTP 400: The `reasoning_content` in the thinking mode must be passed back to the API.",
            httpError(400),
        )
        coEvery { second.completeWithTools(any(), any()) } returns expected

        val result = RoutedProviderChain(listOf(first, second)).completeWithTools(
            listOf(LlmMessage("user", "continue the tool task")),
            emptyList(),
        )

        assertSame(expected, result)
        coVerify(exactly = 1) { second.completeWithTools(any(), any()) }
    }

    @Test
    fun `stream does not replay on another provider after output begins`() = runTest {
        val first = provider("Groq", "kimi")
        val second = provider("OpenRouter", "nemotron")
        every { first.stream(any()) } returns flowOf(
            LlmStreamChunk.Delta("partial"),
            LlmStreamChunk.Error("limited", httpError(429)),
        )
        val chain = RoutedProviderChain(listOf(first, second))

        val chunks = chain.stream(listOf(LlmMessage("user", "hello"))).toList()

        assertEquals(2, chunks.size)
        assertEquals(LlmStreamChunk.Delta("partial"), chunks.first())
    }

    private fun provider(providerName: String, modelId: String): LlmProvider =
        mockk(relaxed = true) {
            every { name } returns providerName
            every { model } returns modelId
            every { isOnDevice } returns false
            coEvery { isAvailable() } returns true
        }

    private fun httpError(code: Int): HttpException = HttpException(
        Response.error<Any>(
            code,
            "{}".toResponseBody("application/json".toMediaType()),
        ),
    )
}
