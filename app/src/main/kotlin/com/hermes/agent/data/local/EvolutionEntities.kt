package com.hermes.agent.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One feature-evolution proposal (see docs/FEATURE-EVOLUTION.md).
 *
 * The domain model lives in agent-core
 * ([com.hermes.agent.data.plugin.evolution.EvolutionProposal]); this row is its
 * persistence shape. List fields are stored as JSON arrays so a finding or a
 * criterion containing a newline survives the round trip. [artifact] is the
 * exact module manifest (or change spec) the bots produced — never re-encoded,
 * because [artifactSha256] pins those bytes for install.
 */
@Entity(
    tableName = "evolution_proposals",
    indices = [Index(value = ["status"]), Index(value = ["signalKey"])],
)
data class EvolutionProposalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val problem: String,
    val evidence: String,
    val kind: String,
    /** JSON array of strings. */
    val acceptanceCriteria: String,
    val targetTool: String?,
    val signalKey: String,
    val status: String,
    val statusMessage: String,
    val artifact: String?,
    val artifactSha256: String?,
    val reviewVerdict: String?,
    /** JSON array of strings. */
    val reviewFindings: String,
    val testReport: String,
    val rounds: Int,
    val moduleId: String?,
    val issueUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One installed version of an evolution module, kept so the live version can be
 * rolled back to the one before it. [manifestJson] and [sha256] are the exact
 * approved bytes and their digest; a rollback reinstalls them with that pin.
 */
@Entity(
    tableName = "evolution_module_versions",
    indices = [Index(value = ["moduleId"])],
)
data class EvolutionModuleVersionEntity(
    @PrimaryKey val id: String,
    val moduleId: String,
    val proposalId: String,
    val version: String,
    val manifestJson: String,
    val sha256: String,
    /** Comma-separated, as in `script_plugins.grantedPermissions`. */
    val grantedPermissions: String,
    val installedAt: Long,
    val active: Boolean,
)

/** A tool-call row projected from `activity_ledger` for usage mining. */
data class EvolutionToolCallRow(
    val tool: String,
    val success: Boolean,
    val detail: String,
    val timestamp: Long,
)

/** A chat row projected from `messages` for usage mining. */
data class EvolutionChatRow(
    val role: String,
    val content: String,
    val conversationId: String,
    val timestamp: Long,
)

/** How often one skill was rewritten, projected from `skill_revisions`. */
data class EvolutionSkillChurnRow(
    val name: String,
    val count: Int,
)
