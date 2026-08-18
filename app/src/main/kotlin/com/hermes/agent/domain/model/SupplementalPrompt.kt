package com.hermes.agent.domain.model

/**
 * Durable, refinable guidance layered on top of an agent's base system prompt.
 *
 * The base prompt is the `systemPrompt` val hardcoded in each agent class
 * under `data/agent/agents`. It is immutable by design: it declares
 * what tools exist and how the agent is wired, and nothing that learns from
 * usage is allowed to rewrite it. The supplemental prompt is the part that
 * *can* be learned — operating notes accumulated from how the user actually
 * works — and it is appended, never substituted.
 *
 * A role with no row, or a blank [content], injects nothing at all.
 */
data class SupplementalPrompt(
    val role: AgentRole,
    val content: String,
    val version: String,
    val updatedAt: Long,
) {
    val isEmpty: Boolean get() = content.isBlank()
}

/** A snapshot of a supplemental prompt as it stood before an edit replaced it. */
data class PromptRevision(
    val id: String,
    val role: AgentRole,
    val version: String,
    val content: String,
    val note: String,
    val replacedAt: Long,
)
