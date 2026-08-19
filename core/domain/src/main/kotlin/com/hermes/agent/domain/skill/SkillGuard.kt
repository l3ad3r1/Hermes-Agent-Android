package com.hermes.agent.domain.skill

/**
 * Skills Guard — static vetting of skill content before it is saved or
 * followed. Ported from hermes-agent's in-process security heuristics
 * (SECURITY.md): skills are markdown instructions injected into the LLM
 * prompt, so the threat model is prompt injection and malicious
 * instructions, not code execution.
 *
 * Checks (all case-insensitive):
 *  - **Prompt injection**: attempts to override the system prompt
 *    ("ignore previous instructions", "you are no longer", …).
 *  - **Secret exfiltration**: instructions pairing credentials with an
 *    outbound channel (send/post/upload/webhook/notify).
 *  - **Destructive shell**: rm -rf /, mkfs, dd of=/dev/, fork bombs.
 *  - **Obfuscation**: long base64 blobs or zero-width unicode characters
 *    that could hide instructions from human review.
 *
 * Enforcement points:
 *  - [com.hermes.agent.data.agent.AutonomousSkillCreator] — flagged
 *    auto-generated skills are not saved.
 *  - [com.hermes.agent.work.SkillImprovementWorker] — flagged rewrites are
 *    discarded (the previous body is kept).
 *  - [com.hermes.agent.data.backup.GithubBackupService] — flagged skills in
 *    a restored backup are skipped (gist content is remote input).
 *  - [com.hermes.agent.data.tools.SkillManagerTool] — pre-existing flagged
 *    skills are still viewable but served with a caution banner.
 */
object SkillGuard {

    data class Verdict(val ok: Boolean, val flags: List<String>) {
        companion object {
            val OK = Verdict(true, emptyList())
        }
    }

    private val PROMPT_INJECTION = listOf(
        Regex("""(?i)ignore\s+(all\s+|any\s+)?(previous|prior|above|earlier)\s+(instructions|rules|prompts)"""),
        Regex("""(?i)disregard\s+(your|the)\s+system\s+prompt"""),
        Regex("""(?i)you\s+are\s+no\s+longer\s+(an?\s+)?(assistant|agent|ai)"""),
        Regex("""(?i)do\s+not\s+(tell|inform|alert)\s+the\s+user"""),
        Regex("""(?i)without\s+(asking|telling|informing)\s+the\s+user"""),
    )

    private val EXFILTRATION = listOf(
        Regex("""(?i)(api[ _-]?key|token|password|secret|credential)s?\b[^\n]{0,80}\b(send|post|upload|transmit|forward|exfiltrat\w*|webhook|notify)"""),
        Regex("""(?i)\b(send|post|upload|transmit|forward|exfiltrat\w*)\b[^\n]{0,80}\b(api[ _-]?key|token|password|secret|credential)s?"""),
    )

    private val DESTRUCTIVE_SHELL = listOf(
        Regex("""rm\s+(-[a-z]*r[a-z]*f|-[a-z]*f[a-z]*r)[a-z]*\s+[/~]"""),
        Regex("""(?i)\bmkfs(\.\w+)?\b"""),
        Regex("""\bdd\s+[^\n]*of=/dev/"""),
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;"""),
        Regex("""(?i)\bformat\s+c:"""),
    )

    // Zero-width and invisible code points written as regex-engine escapes
    // so the guard's own source contains none of them.
    private val OBFUSCATION = listOf(
        Regex("""[A-Za-z0-9+/=]{120,}""") to "long base64-like blob",
        Regex("[\\u200B-\\u200F\\u2060\\uFEFF]") to "invisible unicode characters",
    )

    fun vet(content: String): Verdict {
        val flags = mutableListOf<String>()

        if (PROMPT_INJECTION.any { it.containsMatchIn(content) }) {
            flags += "prompt-injection phrasing"
        }
        if (EXFILTRATION.any { it.containsMatchIn(content) }) {
            flags += "credential exfiltration instruction"
        }
        if (DESTRUCTIVE_SHELL.any { it.containsMatchIn(content) }) {
            flags += "destructive shell command"
        }
        for ((pattern, label) in OBFUSCATION) {
            if (pattern.containsMatchIn(content)) flags += label
        }

        return if (flags.isEmpty()) Verdict.OK else Verdict(false, flags)
    }
}
