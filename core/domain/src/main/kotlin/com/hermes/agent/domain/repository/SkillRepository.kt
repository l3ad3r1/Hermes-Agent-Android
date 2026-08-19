package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.model.SkillRevision
import kotlinx.coroutines.flow.Flow

interface SkillRepository {
    fun observe(): Flow<List<Skill>>
    suspend fun getAll(): List<Skill>
    suspend fun getByName(name: String): Skill?
    suspend fun upsert(
        name: String,
        description: String,
        content: String,
        category: String = "general",
        tags: List<String> = emptyList(),
        version: String = "1.0.0",
        requiresTools: List<String> = emptyList(),
        fallbackForTools: List<String> = emptyList(),
        /**
         * Why this edit happened. When a skill already exists and its content
         * or description actually changes, the outgoing version is archived
         * with this note so [restore] can bring it back. Pass null for edits
         * not worth keeping history for.
         */
        revisionNote: String? = null,
    ): Skill

    /** Archived prior versions of a skill, newest first. */
    suspend fun revisions(skillName: String, limit: Int = 20): List<SkillRevision>

    /**
     * Roll a skill back to an archived revision. The version moves *forward*
     * (a patch bump) rather than back to the archived string, so the history
     * still reads in order, and the version being replaced is itself archived
     * — restoring is undoable too. Returns null if the revision or its skill
     * is gone.
     */
    suspend fun restore(revisionId: String): Skill?
    suspend fun delete(id: String)
    suspend fun seedBuiltIn()

    /** Record that the agent loaded this skill's full content: bumps
     *  useCount, stamps lastUsedAt, and revives STALE/ARCHIVED → ACTIVE. */
    suspend fun recordUse(name: String)

    /** Pin/unpin a skill. Pinned skills bypass curator auto-transitions. */
    suspend fun setPinned(id: String, pinned: Boolean)

    /**
     * Curator auto-transitions (ported from hermes-agent's
     * apply_automatic_transitions): non-builtin, non-pinned skills go
     * ACTIVE → STALE after [staleAfterDays] without use and STALE → ARCHIVED
     * after [archiveAfterDays]. Skills never used fall back to their
     * updatedAt timestamp. Never deletes. Returns counts of transitions
     * applied as (staled, archived).
     */
    suspend fun applyLifecycleTransitions(
        staleAfterDays: Int = 30,
        archiveAfterDays: Int = 90,
        now: Long = System.currentTimeMillis(),
    ): Pair<Int, Int>
}
