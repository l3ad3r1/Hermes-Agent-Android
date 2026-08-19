package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.PromptRevision
import com.hermes.agent.domain.model.SupplementalPrompt

/**
 * Durable store for the learnable half of each agent's system prompt.
 * See [SupplementalPrompt] for why the base prompt is never touched.
 */
interface SupplementalPromptRepository {

    /** The prompt for a role, or null when the role has no learned guidance. */
    suspend fun get(role: AgentRole): SupplementalPrompt?

    /** Every role's prompt, keyed by role — used to prefetch before a run. */
    suspend fun getAll(): Map<AgentRole, SupplementalPrompt>

    /**
     * Write a role's supplemental prompt, archiving the outgoing content when
     * it actually changed. Passing blank [content] clears the role's guidance
     * (the previous version is still archived, so it can be brought back).
     */
    suspend fun put(
        role: AgentRole,
        content: String,
        version: String,
        revisionNote: String? = null,
    ): SupplementalPrompt

    /** Archived prior versions for a role, newest first. */
    suspend fun revisions(role: AgentRole, limit: Int = 20): List<PromptRevision>

    /**
     * Roll a role's prompt back to an archived revision. As with skills the
     * version moves forward rather than back, and the content being replaced
     * is archived first, so a restore is itself undoable.
     */
    suspend fun restore(revisionId: String): SupplementalPrompt?
}
