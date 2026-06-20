package com.hermes.agent.data.llm

import app.cash.turbine.test
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
    }

    @Test
    fun `routes to cloud when request is complex and cloud is available`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            onDeviceEnabled = true,
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { onDevice.isAvailable() } returns true
        coEvery { cloud.isAvailable() } returns true

        val router = HybridLlmRouter(onDevice, cloud, settings)
        val complexPrompt = "Please plan a multi-step research project comparing the " +
            "Romantic and Enlightenment philosophical traditions, then design a long-form essay " +
            "outlining the comparison in detail with citations. " + "x".repeat(500)
        val decision = router.route(listOf(LlmMessage("user", complexPrompt)))

        assertTrue("expected Cloud decision", decision is RoutingDecision.Cloud)
    }

    @Test
    fun `routes to on-device when request is simple`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            onDeviceEnabled = true,
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { onDevice.isAvailable() } returns true
        coEvery { cloud.isAvailable() } returns true

        val router = HybridLlmRouter(onDevice, cloud, settings)
        val decision = router.route(listOf(LlmMessage("user", "Hello")))

        assertTrue("expected OnDevice decision", decision is RoutingDecision.OnDevice)
    }

    @Test
    fun `returns unavailable when neither provider is enabled`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            onDeviceEnabled = false,
            cloudEnabled = false,
        )

        val router = HybridLlmRouter(onDevice, cloud, settings)
        val decision = router.route(listOf(LlmMessage("user", "anything")))

        assertTrue("expected Unavailable", decision is RoutingDecision.Unavailable)
    }

    @Test
    fun `returns unavailable when cloud is enabled but no API key set`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            onDeviceEnabled = false,
            cloudEnabled = true,
            cloudApiKey = "",
        )
        coEvery { cloud.isAvailable() } returns false

        val router = HybridLlmRouter(onDevice, cloud, settings)
        val decision = router.route(listOf(LlmMessage("user", "anything")))

        assertTrue(decision is RoutingDecision.Unavailable)
        val unavailable = decision as RoutingDecision.Unavailable
        assertTrue(
            "reason should mention API key",
            unavailable.reason.contains("API key", ignoreCase = true)
        )
    }

    @Test
    fun `complexity classifier flags trigger words`() {
        assertEquals(
            RequestComplexity.COMPLEX,
            ComplexityClassifier.classify("Please summarize this article")
        )
        assertEquals(
            RequestComplexity.COMPLEX,
            ComplexityClassifier.classify("compare Kotlin and Dart for Android development")
        )
        assertEquals(
            RequestComplexity.SIMPLE,
            ComplexityClassifier.classify("hello")
        )
    }

    @Test
    fun `complexity classifier flags long prompts`() {
        val long = "a".repeat(500)
        assertEquals(RequestComplexity.COMPLEX, ComplexityClassifier.classify(long))
    }

    @Test
    fun `router emits deterministic decisions across calls`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            onDeviceEnabled = true,
            cloudEnabled = false,
        )
        coEvery { onDevice.isAvailable() } returns true
        coEvery { cloud.isAvailable() } returns false

        val router = HybridLlmRouter(onDevice, cloud, settings)
        val d1 = router.route(listOf(LlmMessage("user", "hi")))
        val d2 = router.route(listOf(LlmMessage("user", "hi")))
        assertEquals(d1::class, d2::class)
    }
}
