package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.Skill
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
    ): Skill
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
