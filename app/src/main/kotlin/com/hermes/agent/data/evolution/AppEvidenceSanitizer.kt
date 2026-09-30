package com.hermes.agent.data.evolution

import com.hermes.agent.data.diagnostics.ReportRedactor
import com.hermes.agent.data.plugin.evolution.EvidenceSanitizer

/**
 * The app's rules for evidence that leaves the phone — to the proposal model, a
 * desktop bot, or a GitHub issue. Reuses the existing patterns rather than a
 * copy: [TraceHeuristics] for credentials (the refiners' rules) and
 * [ReportRedactor] for personal data (the problem-report rules).
 *
 * Text that looked like it carried a credential is dropped entirely, not just
 * redacted: evidence is only ever illustrative, so losing one example costs
 * nothing, while a partial redaction that misses a variant would leak it.
 */
object AppEvidenceSanitizer : EvidenceSanitizer {

    override fun sanitize(text: String): String? {
        if (TraceHeuristics.containsSecret(text)) return null
        val redacted = ReportRedactor.redact(TraceHeuristics.redact(text)).trim()
        return redacted.takeIf { it.isNotEmpty() && !TraceHeuristics.containsSecret(it) }
    }
}
