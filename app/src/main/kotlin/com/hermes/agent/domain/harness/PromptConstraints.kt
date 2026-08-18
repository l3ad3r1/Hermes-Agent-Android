package com.hermes.agent.domain.harness

/**
 * Hard gates a proposed supplemental prompt must clear before it can be
 * adopted, mirroring [com.hermes.agent.domain.skill.SkillConstraints].
 *
 * The budget here is far tighter than a skill's. A skill body is loaded only
 * on the turns where it matches; a supplemental prompt is prepended to *every*
 * call that role makes, so its cost is paid on each turn forever and an
 * unbounded one would slowly crowd out the conversation itself.
 */
object PromptConstraints {

    /** Roughly a few hundred tokens — enough for operating notes, not an essay. */
    const val MAX_PROMPT_CHARS = 2_000

    /** Below this it is not guidance, it is noise. */
    const val MIN_PROMPT_CHARS = 20

    /** A rewrite may not balloon beyond 1.5× what it replaced. */
    const val MAX_GROWTH_RATIO = 1.5f

    data class Result(val name: String, val passed: Boolean, val message: String)

    /**
     * Validate a candidate supplemental prompt. [baseline] is the content
     * being replaced; pass null when there is nothing to grow from.
     */
    fun validate(content: String, baseline: String? = null): List<Result> {
        val results = mutableListOf<Result>()
        val trimmed = content.trim()

        results += Result(
            "size",
            trimmed.length <= MAX_PROMPT_CHARS,
            "${trimmed.length} chars (limit $MAX_PROMPT_CHARS)",
        )

        results += Result(
            "non_empty",
            trimmed.length >= MIN_PROMPT_CHARS,
            "${trimmed.length} chars (minimum $MIN_PROMPT_CHARS)",
        )

        // Only meaningful once there is a non-trivial baseline: going from an
        // empty prompt to a first real one is not "growth", it is the point.
        if (baseline != null && baseline.trim().length >= MIN_PROMPT_CHARS) {
            val base = baseline.trim().length
            val ratio = trimmed.length.toFloat() / base
            results += Result(
                "growth",
                ratio <= MAX_GROWTH_RATIO,
                "×${"%.2f".format(ratio)} of previous",
            )
        }

        return results
    }

    fun allPass(results: List<Result>): Boolean = results.all { it.passed }
}
