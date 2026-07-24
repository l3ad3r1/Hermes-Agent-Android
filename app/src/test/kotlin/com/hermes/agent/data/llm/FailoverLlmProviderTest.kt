package com.hermes.agent.data.llm

import com.hermes.agent.domain.tool.ToolDescriptor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class FailoverLlmProviderTest {
    private lateinit var cloud: LlmProvider
    private lateinit var local: LlmProvider
    private lateinit var provider: FailoverLlmProvider

    @Before
    fun setUp() {
        cloud = mockk(relaxed = true) {
            every { name } returns "cloud"
            every { isOnDevice } returns false
            every { model } returns "cloud-model"
        }
        local = mockk(relaxed = true) {
            every { name } returns "local"
            every { isOnDevice } returns true
            every { model } returns "local-model"
            coEvery { isAvailable() } returns true
        }
        provider = FailoverLlmProvider(cloud, local)
    }

    @Test
    fun `tool completion falls back to local after cloud transport failure`() = runTest {
        val messages = listOf(LlmMessage("user", "hello"))
        val tools = emptyList<ToolDescriptor>()
        val localResponse = LlmToolResponse("offline answer", emptyList(), 4, "local-model", "stop")
        coEvery { cloud.completeWithTools(messages, tools) } throws IOException("network down")
        coEvery { local.completeWithTools(messages, tools) } returns localResponse

        val result = provider.completeWithTools(messages, tools)

        assertSame(localResponse, result)
        assertSame(localResponse, provider.completeWithTools(messages, tools))
        assertTrue(provider.isOnDevice)
        assertEquals("local-model", provider.model)
        coVerify(exactly = 1) { cloud.completeWithTools(messages, tools) }
        coVerify(exactly = 2) { local.completeWithTools(messages, tools) }
    }

    @Test
    fun `ordinary completion falls back to local after cloud transport failure`() = runTest {
        val messages = listOf(LlmMessage("user", "hello"))
        val localResponse = LlmResponse("offline answer", 4, "local-model")
        coEvery { cloud.complete(messages) } throws IOException("network down")
        coEvery { local.complete(messages) } returns localResponse

        assertSame(localResponse, provider.complete(messages))
        coVerify(exactly = 1) { cloud.complete(messages) }
        coVerify(exactly = 1) { local.complete(messages) }
    }

    @Test
    fun `HTTP provider errors remain visible and do not fall back`() = runTest {
        val messages = listOf(LlmMessage("user", "hello"))
        val error = HttpException(
            Response.error<Any>(
                401,
                "{}".toResponseBody("application/json".toMediaType()),
            ),
        )
        coEvery { cloud.complete(messages) } throws error

        val failure = runCatching { provider.complete(messages) }.exceptionOrNull()

        assertSame(error, failure)
        assertFalse(provider.isOnDevice)
        coVerify(exactly = 0) { local.isAvailable() }
        coVerify(exactly = 0) { local.complete(any()) }
    }

    @Test
    fun `stream falls back before output when cloud reports transport failure`() = runTest {
        val messages = listOf(LlmMessage("user", "hello"))
        every { cloud.stream(messages) } returns flowOf(
            LlmStreamChunk.Error("network down", IOException("network down")),
        )
        every { local.stream(messages) } returns flowOf(
            LlmStreamChunk.Delta("offline"),
            LlmStreamChunk.Done,
        )

        val chunks = provider.stream(messages).toList()

        assertEquals(listOf(LlmStreamChunk.Delta("offline"), LlmStreamChunk.Done), chunks)
        assertTrue(provider.isOnDevice)
        verify(exactly = 1) { local.stream(messages) }
    }

    @Test
    fun `stream never restarts locally after cloud output was emitted`() = runTest {
        val messages = listOf(LlmMessage("user", "hello"))
        val failure = IOException("connection reset")
        every { cloud.stream(messages) } returns flowOf(
            LlmStreamChunk.Delta("partial"),
            LlmStreamChunk.Error("connection reset", failure),
        )

        val chunks = provider.stream(messages).toList()

        assertEquals(LlmStreamChunk.Delta("partial"), chunks[0])
        assertTrue(chunks[1] is LlmStreamChunk.Error)
        assertFalse(provider.isOnDevice)
        verify(exactly = 0) { local.stream(any()) }
    }
}