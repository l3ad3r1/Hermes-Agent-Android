package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityAwareLlmRoutingPolicyTest {

    private val policy = QualityAwareLlmRoutingPolicy()

    @Test
    fun `selects zero-cost local model for a simple request`() {
        val ranked = policy.rank(
            messages = listOf(LlmMessage("user", "hello")),
            context = RoutingContext(),
            candidates = candidates(),
        )

        assertEquals(LlmModelTier.ON_DEVICE, ranked.first().candidate.tier)
        assertTrue(ranked.first().satisfiesRequirements)
    }

    @Test
    fun `selects specialist for a complex request`() {
        val ranked = policy.rank(
            messages = listOf(LlmMessage("user", "Analyze and compare these architectures in detail")),
            context = RoutingContext(),
            candidates = candidates(),
        )

        assertEquals(LlmModelTier.SPECIALIST_CLOUD, ranked.first().candidate.tier)
        assertTrue(ranked.first().requiredQuality >= 0.85)
    }

    @Test
    fun `tool reliability gate excludes local model`() {
        val ranked = policy.rank(
            messages = listOf(LlmMessage("user", "Create a calendar event")),
            context = RoutingContext(requiresReliableToolCalls = true),
            candidates = candidates(),
        )

        assertEquals(LlmModelTier.PRIMARY_CLOUD, ranked.first().candidate.tier)
        assertTrue(ranked.all { it.candidate.toolReliability >= 0.85 })
    }

    @Test
    fun `returns best available model when no candidate clears the gate`() {
        val localOnly = candidates().filter { it.candidateTier() == LlmModelTier.ON_DEVICE }
        val ranked = policy.rank(
            messages = listOf(LlmMessage("user", "Analyze this multi-step plan")),
            context = RoutingContext(requiresReliableToolCalls = true),
            candidates = localOnly,
        )

        assertEquals(LlmModelTier.ON_DEVICE, ranked.single().candidate.tier)
        assertFalse(ranked.single().satisfiesRequirements)
    }

    @Test
    fun `ultrabrain alias boosts specialist cloud even for simple requests`() {
        val ranked = policy.rank(
            messages = listOf(LlmMessage("user", "hello")),
            context = RoutingContext(requiredAlias = "ultrabrain"),
            candidates = candidates(),
        )

        assertEquals(LlmModelTier.SPECIALIST_CLOUD, ranked.first().candidate.tier)
    }

    @Test
    fun `quick alias boosts primary cloud for complex tasks`() {
        val ranked = policy.rank(
            messages = listOf(LlmMessage("user", "Analyze and compare these options")),
            context = RoutingContext(requiredAlias = "quick"),
            candidates = candidates(),
        )

        assertEquals(LlmModelTier.PRIMARY_CLOUD, ranked.first().candidate.tier)
    }

    private fun candidates(): List<LlmRouteCandidate> = listOf(
        candidate(LlmModelTier.ON_DEVICE, quality = 0.48, cost = 0.0, latency = 0.65, tools = 0.50),
        candidate(LlmModelTier.PRIMARY_CLOUD, quality = 0.78, cost = 0.35, latency = 0.80, tools = 0.90),
        candidate(LlmModelTier.SPECIALIST_CLOUD, quality = 0.94, cost = 0.85, latency = 0.45, tools = 0.96),
    )

    private fun candidate(
        tier: LlmModelTier,
        quality: Double,
        cost: Double,
        latency: Double,
        tools: Double,
    ): LlmRouteCandidate {
        val provider = mockk<LlmProvider>(relaxed = true)
        every { provider.name } returns tier.name
        every { provider.model } returns tier.name.lowercase()
        every { provider.isOnDevice } returns (tier == LlmModelTier.ON_DEVICE)
        return LlmRouteCandidate(provider, tier, quality, cost, latency, tools)
    }

    private fun LlmRouteCandidate.candidateTier(): LlmModelTier = tier
}
