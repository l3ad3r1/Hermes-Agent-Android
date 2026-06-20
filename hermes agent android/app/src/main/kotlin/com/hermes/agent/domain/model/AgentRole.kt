package com.hermes.agent.domain.model

/**
 * Specialized agent personas from the multi-agent roster described in
 * Section 6.1 of the technical plan.
 *
 * Phase 1 only ships the [CONVERSATIONAL] agent end-to-end. The remaining
 * agents are declared here so the UI and persistence layer can already
 * record which agent produced a message; their orchestration logic lands
 * in Phase 2.
 */
enum class AgentRole(val displayName: String, val description: String) {
    CONVERSATIONAL(
        displayName = "Conversational",
        description = "Natural dialogue, small talk, and clarifying questions."
    ),
    PRODUCTIVITY(
        displayName = "Productivity",
        description = "Calendar, tasks, email drafting, scheduling."
    ),
    RESEARCH(
        displayName = "Research",
        description = "Web search, document analysis, summarization."
    ),
    DEVICE_CONTROL(
        displayName = "Device Control",
        description = "System settings, app launching, notifications."
    ),
    CREATIVE(
        displayName = "Creative",
        description = "Writing assistance, brainstorming, content generation."
    );

    companion object {
        /** Default agent when no router decision has been made yet. */
        val DEFAULT = CONVERSATIONAL
    }
}
