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

    private lateinit var cloud: CloudLlmProvider
    private lateinit var specialised: CloudLlmProvider
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        cloud = mockk(relaxed = true)
        specialised = mockk(relaxed = true)
        settings = mockk(relaxed = true)
    }

    @Test
    fun `routes to cloud when enabled and key is set`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, settings)
        val decision = router.route(listOf(LlmMessage("user", "hello")))

        assertTrue("expected Cloud decision", decision is RoutingDecision.Cloud)
    }

    @Test
    fun `routes complex tasks to the primary model`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { specialised.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, settings)
        val decision = router.route(listOf(LlmMessage("user", "Please analyze and compare these options")))

        assertTrue(decision is RoutingDecision.Cloud)
        assertEquals(cloud, (decision as RoutingDecision.Cloud).provider)
    }

    @Test
    fun `routes simple tasks to the specialised model`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { specialised.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, settings)
        val decision = router.route(listOf(LlmMessage("user", "hi")))

        assertTrue(decision is RoutingDecision.Cloud)
        assertEquals(specialised, (decision as RoutingDecision.Cloud).provider)
    }

    @Test
    fun `falls back to primary when specialised model is unavailable`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { specialised.isAvailable() } returns false

        val router = HybridLlmRouter(cloud, specialised, settings)
        val decision = router.route(listOf(LlmMessage("user", "hi")))

        assertTrue(decision is RoutingDecision.Cloud)
        assertEquals(cloud, (decision as RoutingDecision.Cloud).provider)
    }

    @Test
    fun `returns unavailable when cloud disabled`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = false,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns false

        val router = HybridLlmRouter(cloud, specialised, settings)
        val decision = router.route(listOf(LlmMessage("user", "hello")))

        assertTrue("expected Unavailable", decision is RoutingDecision.Unavailable)
    }

    @Test
    fun `returns unavailable when cloud enabled but no API key`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "",
        )
        coEvery { cloud.isAvailable() } returns false

        val router = HybridLlmRouter(cloud, specialised, settings)
        val decision = router.route(listOf(LlmMessage("user", "anything")))

        assertTrue(decision is RoutingDecision.Unavailable)
        val unavailable = decision as RoutingDecision.Unavailable
        assertTrue(
            "reason should mention API key",
            unavailable.reason.contains("API key", ignoreCase = true),
        )
    }

    @Test
    fun `returns deterministic decisions across calls`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, settings)
        val d1 = router.route(listOf(LlmMessage("user", "hi")))
        val d2 = router.route(listOf(LlmMessage("user", "hi")))
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
