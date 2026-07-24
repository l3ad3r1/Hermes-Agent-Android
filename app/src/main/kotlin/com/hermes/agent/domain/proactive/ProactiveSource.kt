package com.hermes.agent.domain.proactive

/**
 * A capability that can proactively ping the user. Consent is granted per
 * source (roadmap v0.12): everything defaults OFF except time-based
 * scheduled tasks, which the user configured explicitly.
 */
enum class ProactiveSource(val displayName: String, val defaultConsent: Boolean) {
    SCHEDULED_TASK("Scheduled tasks", defaultConsent = true),
    DIGEST("Daily digest", defaultConsent = false),
    NUDGE("Commitment nudges", defaultConsent = false),
}
