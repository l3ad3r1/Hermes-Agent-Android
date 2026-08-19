package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted supplemental prompt, one row per agent role. Absent row means the
 * role has no learned guidance yet and nothing is injected.
 */
@Entity(tableName = "supplemental_prompts")
data class SupplementalPromptEntity(
    /** [com.hermes.agent.domain.model.AgentRole.name]. */
    @PrimaryKey val roleName: String,
    val content: String,
    val version: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Archived prior version of a supplemental prompt.
 *
 * Kept separate from `skill_revisions` rather than folded into a shared table:
 * the two are keyed differently (role name vs skill id) and pruned
 * independently, and a shared table would need a discriminator column that
 * every query then has to remember to filter on.
 */
@Entity(
    tableName = "prompt_revisions",
    indices = [Index(value = ["roleName", "replacedAt"])],
)
data class PromptRevisionEntity(
    @PrimaryKey val id: String,
    val roleName: String,
    val version: String,
    val content: String,
    val note: String,
    val replacedAt: Long,
)
