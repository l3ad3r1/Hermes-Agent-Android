package com.hermes.agent.domain.model

/**
 * Lifecycle states for the skill curator (ported from hermes-agent's
 * curator.py). Transitions are driven by usage recency:
 * ACTIVE → STALE after 30 days unused → ARCHIVED after 90 days unused.
 * Using a skill (skill_manager view) revives it to ACTIVE. Skills are
 * never auto-deleted — ARCHIVED is recoverable. Pinned and built-in
 * skills bypass all auto-transitions.
 */
enum class SkillLifecycle { ACTIVE, STALE, ARCHIVED }

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val content: String,
    val category: String = "general",
    val tags: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Conditional activation (ported from hermes-agent's
     *  `metadata.hermes.requires_tools`): the skill is offered only when
     *  every listed tool is registered. Empty = always offered. */
    val requiresTools: List<String> = emptyList(),
    /** Conditional activation (`fallback_for_tools`): the skill is a manual
     *  fallback for these tools and is HIDDEN while any of them is
     *  available. Empty = not a fallback. */
    val fallbackForTools: List<String> = emptyList(),
    val lifecycleState: SkillLifecycle = SkillLifecycle.ACTIVE,
    /** Pinned skills bypass curator auto-transitions (stale/archive). */
    val pinned: Boolean = false,
    /** Number of times the skill's full content was loaded by the agent. */
    val useCount: Int = 0,
    /** Last time the skill was loaded (skill_manager view), or null if never. */
    val lastUsedAt: Long? = null,
)
