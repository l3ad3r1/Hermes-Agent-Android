package com.hermes.agent.data.evolution

/**
 * Cheap, deterministic heuristics for turning raw chat history into
 * skill-relevant traces — ported from hermes-agent-self-evolution's
 * `external_importers.py` (`_is_relevant_to_skill`, secret patterns).
 */
object TraceHeuristics {

    private val SECRET = Regex(
        listOf(
            "sk-ant-api\\S+",
            "sk-or-v1-\\S+",
            "sk-\\S{20,}",
            "ghp_\\S+",
            "ghu_\\S+",
            "xox[baprs]-\\S+",
            "AKIA[0-9A-Z]{16}",
            "Bearer\\s+\\S{20,}",
            "-----BEGIN\\s+(RSA\\s+)?PRIVATE\\sKEY-----",
            "\\bpassword\\s*[=:]\\s*\\S+",
            "\\bsecret\\s*[=:]\\s*\\S+",
            "\\btoken\\s*[=:]\\s*\\S{10,}",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    fun containsSecret(text: String): Boolean = SECRET.containsMatchIn(text)

    /** Placeholder written in place of anything [SECRET] matches. */
    const val REDACTION = "[REDACTED]"

    /**
     * Replace every secret-looking span with [REDACTION].
     *
     * The on-device refiners drop a whole trace when it looks like it contains
     * a credential, which is the right call when the trace is only evidence.
     * Anything that *leaves* the device needs the other behaviour — keep the
     * text, lose the secret — so both share these patterns rather than growing
     * a second, drifting copy.
     */
    fun redact(text: String): String = SECRET.replace(text, REDACTION)

    /**
     * Keyword-overlap relevance between a message and a skill. Mirrors the
     * upstream pre-filter: full-name match, any skill-name word > 3 chars, or
     * ≥ 2 shared keywords from the first 500 chars of the skill text.
     */
    fun isRelevantToSkill(text: String, skillName: String, skillText: String): Boolean {
        val textLower = text.lowercase()
        val skillLower = skillName.lowercase().replace('-', ' ').replace('_', ' ')

        if (skillLower.isNotBlank() && textLower.contains(skillLower)) return true

        for (word in skillLower.split(' ')) {
            if (word.length > 3 && textLower.contains(word)) return true
        }

        val skillKeywords = skillText.take(500).lowercase().split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-z]"), "") }
            .filter { it.length > 4 }
            .toSet()

        val messageWords = textLower.replace(Regex("[^a-z\\s]"), "").split(Regex("\\s+")).toSet()
        return (messageWords intersect skillKeywords).size >= 2
    }
}
