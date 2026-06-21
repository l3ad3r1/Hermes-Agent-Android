package com.hermes.agent.data.llm

import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HybridLlmRouterTest {

    private lateinit var onDevice: OnDeviceLlmProvider
    private lateinit var cloud: CloudLlmProvider
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        onDevice = mockk(relaxed = true)
        cloud = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        // On-device disabled by default in tests
        coEvery { onDevice.isAvailable() } returns false
    }

    private fun router() = HybridLlmRouter(onDevice, cloud, settings)

    @Test
    fun `routes to cloud when enabled and key is set`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true

        val decision = router().route(listOf(LlmMessage("user", "hello")))

        assertTrue("expected Cloud decision", decision is RoutingDecision.Cloud)
    }

    @Test
    fun `returns unavailable when cloud disabled`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = false,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns false

        val decision = router().route(listOf(LlmMessage("user", "hello")))

        assertTrue("expected Unavailable", decision is RoutingDecision.Unavailable)
    }

    @Test
    fun `returns unavailable when cloud enabled but no API key`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "",
        )
        coEvery { cloud.isAvailable() } returns false

        val decision = router().route(listOf(LlmMessage("user", "anything")))

        assertTrue(decision is RoutingDecision.Unavailable)
        val unavailable = decision as RoutingDecision.Unavailable
        assertTrue(
            "reason should mention API key",
            unavailable.reason.contains("API key", ignoreCase = true),
        )
    }

    @Test
    fun `routes to on-device when enabled and available`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            onDeviceEnabled = true,
            onDeviceModelPath = "/sdcard/models/test.gguf",
        )
        coEvery { onDevice.isAvailable() } returns true

        val decision = router().route(listOf(LlmMessage("user", "hello")))

        assertTrue("expected OnDevice decision", decision is RoutingDecision.OnDevice)
    }

    @Test
    fun `falls back to cloud when on-device unavailable`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            onDeviceEnabled = true,
            onDeviceModelPath = "",   // blank path → not available
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { onDevice.isAvailable() } returns false
        coEvery { cloud.isAvailable() } returns true

        val decision = router().route(listOf(LlmMessage("user", "hello")))

        assertTrue("expected Cloud fallback", decision is RoutingDecision.Cloud)
    }

    @Test
    fun `returns deterministic decisions across calls`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true

        val r = router()
        val d1 = r.route(listOf(LlmMessage("user", "hi")))
        val d2 = r.route(listOf(LlmMessage("user", "hi")))
        assertEquals(d1::class, d2::class)
    }

    @Test
    fun `complexity classifier flags trigger words`() {
        assertEquals(
            RequestComplexity.COMPLEX,
            ComplexityClassifier.classify("Please summarize this article"),
        )
        assertEquals(
            RequestComplexity.COMPLEX,
            ComplexityClassifier.classify("compare Kotlin and Dart for Android development"),
        )
        assertEquals(
            RequestComplexity.SIMPLE,
            ComplexityClassifier.classify("hello"),
        )
    }

    @Test
    fun `complexity classifier flags long prompts`() {
        val long = "a".repeat(500)
        assertEquals(RequestComplexity.COMPLEX, ComplexityClassifier.classify(long))
    }
}
