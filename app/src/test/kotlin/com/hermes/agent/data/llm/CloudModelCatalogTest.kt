package com.hermes.agent.data.llm

import com.hermes.agent.data.remote.OpenAiApi
import com.hermes.agent.data.remote.dto.ModelInfo
import com.hermes.agent.data.remote.dto.ModelListResponse
import com.hermes.agent.util.DispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.net.SocketException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CloudModelCatalogTest {

    private lateinit var api: OpenAiApi
    private lateinit var dispatchers: DispatcherProvider
    private lateinit var catalog: OpenAiCloudModelCatalog
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() {
        api = mockk()
        dispatchers = mockk()
        io.mockk.every { dispatchers.io } returns dispatcher
        catalog = OpenAiCloudModelCatalog(api, dispatchers)
    }

    @Test
    fun `loads all nonblank unique models from the configured endpoint`() = runTest(dispatcher) {
        coEvery { api.models(any(), any()) } returns ModelListResponse(
            data = listOf(
                ModelInfo("gpt-4o-mini"),
                ModelInfo(" provider/reasoning-large "),
                ModelInfo("gpt-4o-mini"),
                ModelInfo(""),
            ),
        )

        val models = catalog.listModels("https://models.example/v1/", "sk-test")

        assertEquals(listOf("gpt-4o-mini", "provider/reasoning-large"), models)
        coVerify(exactly = 1) {
            api.models("https://models.example/v1/models", "Bearer sk-test")
        }
    }

    @Test
    fun `does not duplicate models path when full catalogue URL is supplied`() = runTest(dispatcher) {
        coEvery { api.models(any(), any()) } returns ModelListResponse()

        catalog.listModels("https://models.example/v1/models", "")

        coVerify(exactly = 1) {
            api.models("https://models.example/v1/models", null)
        }
    }

    @Test
    fun `retries one socket abort then returns models`() = runTest(dispatcher) {
        coEvery { api.models(any(), any()) } throws
            SocketException("Software caused connection abort") andThen
            ModelListResponse(listOf(ModelInfo("recovered-model")))

        val models = catalog.listModels("https://models.example/v1", "key")

        assertEquals(listOf("recovered-model"), models)
        coVerify(exactly = 2) { api.models(any(), any()) }
    }

    @Test
    fun `repeated socket abort becomes actionable error`() = runTest(dispatcher) {
        coEvery { api.models(any(), any()) } throws SocketException("connection abort")

        val failure = runCatching {
            catalog.listModels("https://models.example/v1", "key")
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(
            "Couldn't load models from this provider. Check the URL and connection, then retry.",
            failure?.message,
        )
        coVerify(exactly = 2) { api.models(any(), any()) }
    }

    @Test
    fun `HTTP errors are not retried`() = runTest(dispatcher) {
        val error = HttpException(
            Response.error<ModelListResponse>(
                401,
                "{}".toResponseBody("application/json".toMediaType()),
            ),
        )
        coEvery { api.models(any(), any()) } throws error

        val failure = runCatching {
            catalog.listModels("https://models.example/v1", "bad-key")
        }.exceptionOrNull()

        assertTrue(failure is HttpException)
        coVerify(exactly = 1) { api.models(any(), any()) }
    }

    @Test
    fun `invalid API URL fails before network call`() = runTest(dispatcher) {
        val failure = runCatching {
            catalog.listModels("not a url", "key")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("valid HTTP or HTTPS"))
        coVerify(exactly = 0) { api.models(any(), any()) }
    }
}