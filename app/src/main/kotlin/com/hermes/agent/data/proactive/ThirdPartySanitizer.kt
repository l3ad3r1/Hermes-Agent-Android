package com.hermes.agent.data.proactive

/**
 * Scrubs third-party notification text before it enters the capture store
 * (roadmap v0.12 injection boundary, first line of defence). Removes control
 * and zero-width characters that hide instructions from human review,
 * collapses whitespace, and truncates hard.
 *
 * The second, structural line of defence: captured text is rendered ONLY in
 * the human-facing digest notification. It is never concatenated into an LLM
 * prompt anywhere in the codebase — keep it that way (L-009).
 */
object ThirdPartySanitizer {

    private val CONTROL_OR_INVISIBLE = Regex("[\\p{Cc}\\p{Cf}]")
    private val WHITESPACE_RUNS = Regex("\\s+")

    fun sanitize(raw: String?, maxChars: Int = MAX_CHARS): String =
        raw.orEmpty()
            .replace(CONTROL_OR_INVISIBLE, " ")
            .replace(WHITESPACE_RUNS, " ")
            .trim()
            .take(maxChars)

    const val MAX_CHARS = 140
}
