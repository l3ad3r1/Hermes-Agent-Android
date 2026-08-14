package com.hermes.agent.data.llm

import javax.inject.Inject
import javax.inject.Singleton

/** Extra execution requirements that cannot be inferred from chat text alone. */
data class RoutingContext(
    val requiresReliableToolCalls: Boolean = false,
)

/** The role and normalized operating characteristics of one runnable model. */
data class LlmRouteCandidate(
    val provider: LlmProvider,
    val tier: LlmModelTier,
    val quality: Double,
    val cost: Double,
    val latency: Double,
    val toolReliability: Double,
)

enum class LlmModelTier {
    ON_DEVICE,
    PRIMARY_CLOUD,
    SPECIALIST_CLOUD,
}

data class ScoredLlmRoute(
    val candidate: LlmRouteCandidate,
    val score: Double,
    val requiredQuality: Double,
    val satisfiesRequirements: Boolean,
)

/**
 * Android-side routing boundary inspired by U-Lab's LLMRouter MetaRouter.
 *
 * Training and inference are deliberately separate from provider execution:
 * a future ONNX/TFLite policy can implement this contract without changing the
 * orchestrator or any cloud/local provider.
 */
interface LlmRoutingPolicy {
    fun rank(
        messages: List<LlmMessage>,
        context: RoutingContext,
        candidates: List<LlmRouteCandidate>,
    ): List<ScoredLlmRoute>
}

/**
 * Lightweight adaptation of LLMRouter's Hybrid LLM policy for a phone.
 *
 * Upstream predicts whether a small model's quality clears a threshold. Hermes
 * applies the same quality-gate idea to normalized model profiles, then ranks
 * qualifying models by cost, latency and quality surplus. This has no Python,
 * PyTorch or embedding runtime dependency and is deterministic/offline.
 */
@Singleton
class QualityAwareLlmRoutingPolicy @Inject constructor() : LlmRoutingPolicy {

    override fun rank(
        messages: List<LlmMessage>,
        context: RoutingContext,
        candidates: List<LlmRouteCandidate>,
    ): List<ScoredLlmRoute> {
        if (candidates.isEmpty()) return emptyList()

        val prompt = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val requiredQuality = requiredQuality(prompt, context)
        val minimumToolReliability = if (context.requiresReliableToolCalls) 0.85 else 0.0

        val scored = candidates.map { candidate ->
            val satisfies = candidate.quality >= requiredQuality &&
                candidate.toolReliability >= minimumToolReliability
            val qualitySurplus = candidate.quality - requiredQuality
            val score = if (satisfies) {
                // Among models good enough for this request, favor efficiency.
                (1.0 - candidate.cost) * 0.50 +
                    candidate.latency * 0.20 +
                    qualitySurplus * 0.30
            } else {
                // If nothing clears the gate, degrade toward the most capable
                // candidate rather than failing an otherwise runnable request.
                candidate.quality * 0.65 +
                    candidate.toolReliability * (if (context.requiresReliableToolCalls) 0.25 else 0.0) +
                    (1.0 - candidate.cost) * 0.10
            }
            ScoredLlmRoute(candidate, score, requiredQuality, satisfies)
        }

        val anySatisfies = scored.any { it.satisfiesRequirements }
        return scored
            .asSequence()
            .filter { !anySatisfies || it.satisfiesRequirements }
            .sortedWith(
                compareByDescending<ScoredLlmRoute> { it.score }
                    .thenByDescending { it.candidate.quality }
                    .thenBy { it.candidate.tier.ordinal },
            )
            .toList()
    }

    private fun requiredQuality(prompt: String, context: RoutingContext): Double {
        val complexityFloor = when (ComplexityClassifier.classify(prompt)) {
            RequestComplexity.SIMPLE -> 0.48
            RequestComplexity.COMPLEX -> 0.85
        }
        return if (context.requiresReliableToolCalls) {
            maxOf(complexityFloor, 0.76)
        } else {
            complexityFloor
        }
    }
}
