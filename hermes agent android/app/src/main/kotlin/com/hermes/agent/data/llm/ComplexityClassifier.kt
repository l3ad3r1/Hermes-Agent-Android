package com.hermes.agent.data.llm

/**
 * Request-complexity classification used by [HybridLlmRouter] to decide
 * between on-device and cloud providers (Section 5.1 of the plan).
 *
 * Heuristic in Phase 1:
 *   - Length > 400 chars                       → COMPLEX
 *   - Contains trigger words (plan, analyze, compare, summarize, design,
 *     brainstorm, draft, write a long, explain in detail) → COMPLEX
 *   - Anything else                            → SIMPLE
 *
 * Phase 2 will replace this with a lightweight on-device intent classifier.
 */
enum class RequestComplexity {
    SIMPLE,
    COMPLEX;

    val needsCloud: Boolean get() = this == COMPLEX
}

object ComplexityClassifier {

    private val TRIGGERS = setOf(
        "plan", "analyse", "analyze", "analysis", "compare", "comparison",
        "summarize", "summarise", "design", "brainstorm", "draft",
        "write a long", "write an essay", "write a report",
        "explain in detail", "step by step", "multi-step",
        "evaluate", "critique", "outline",
    )

    fun classify(prompt: String): RequestComplexity {
        if (prompt.length > 400) return RequestComplexity.COMPLEX
        val lowered = prompt.lowercase()
        return if (TRIGGERS.any { lowered.contains(it) }) {
            RequestComplexity.COMPLEX
        } else {
            RequestComplexity.SIMPLE
        }
    }
}
