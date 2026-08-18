package com.hermes.agent.domain.skill

/**
 * Hard constraint gates a refined skill body must pass before it can be
 * proposed for adoption. Ported from hermes-agent-self-evolution's
 * `evolution/core/constraints.py` — a failed gate means the rewrite is
 * rejected and the original skill stays.
 */
object SkillConstraints {

    /** Matches the upstream ≤15KB skill-size cap. */
    const val MAX_SKILL_BYTES = 15_000

    /** A rewrite may not balloon the body beyond 1.5× the baseline. */
    const val MAX_GROWTH_RATIO = 1.5f

    /** A body must have real content, not a stub. */
    const val MIN_BODY_LENGTH = 50

    /** A description must say something; one or two words cannot describe a skill. */
    const val MIN_DESCRIPTION_LENGTH = 10

    data class Result(val name: String, val passed: Boolean, val message: String)

    /**
     * Validate a candidate [body]. When [baselineBody] is supplied, also checks
     * that the rewrite didn't grow disproportionately.
     */
    fun validate(
        body: String,
        baselineBody: String? = null,
        description: String? = null,
    ): List<Result> {
        val results = mutableListOf<Result>()

        val bytes = body.toByteArray(Charsets.UTF_8).size
        results += Result(
            "size",
            bytes <= MAX_SKILL_BYTES,
            "$bytes bytes (limit $MAX_SKILL_BYTES)",
        )

        results += Result(
            "non_empty",
            body.isNotBlank() && body.length >= MIN_BODY_LENGTH,
            "${body.length} chars",
        )

        if (baselineBody != null) {
            val base = baselineBody.length.coerceAtLeast(1)
            val ratio = body.length.toFloat() / base
            results += Result(
                "growth",
                ratio <= MAX_GROWTH_RATIO,
                "×${"%.2f".format(ratio)} of baseline",
            )
        }

        if (description != null) {
            // The description is the retrieval surface SkillMatcher scores
            // against, and it lives on a single YAML frontmatter line: a
            // newline here would truncate the scalar and split the document.
            val singleLine = description.lines().size == 1
            val sized = description.trim().length in
                MIN_DESCRIPTION_LENGTH..SkillDoc.MAX_DESCRIPTION_LENGTH
            results += Result(
                "description",
                singleLine && sized,
                when {
                    !singleLine -> "must be a single line"
                    else -> "${description.trim().length} chars " +
                        "(allowed $MIN_DESCRIPTION_LENGTH..${SkillDoc.MAX_DESCRIPTION_LENGTH})"
                },
            )
        }

        // Structural integrity: a skill body should keep at least one markdown
        // heading so the agent's skill index stays parseable.
        val hasHeading = Regex("(?m)^#{1,6}\\s").containsMatchIn(body)
        results += Result("structure", hasHeading, if (hasHeading) "has headings" else "no markdown headings")

        return results
    }

    fun allPass(results: List<Result>): Boolean = results.all { it.passed }
}
