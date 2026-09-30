package com.hermes.agent.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Feature-evolution persistence plus the read-only usage queries its miner needs.
 *
 * The usage queries read tables owned by other features (`activity_ledger`,
 * `messages`, `skill_revisions`) through narrow projections, so the evolution
 * code never needs those features' DAOs and never writes to their tables.
 */
@Dao
interface EvolutionDao {

    @Query("SELECT * FROM evolution_proposals ORDER BY updatedAt DESC")
    fun observeProposals(): Flow<List<EvolutionProposalEntity>>

    @Query("SELECT * FROM evolution_proposals ORDER BY updatedAt DESC")
    suspend fun allProposals(): List<EvolutionProposalEntity>

    @Query("SELECT * FROM evolution_proposals WHERE id = :id LIMIT 1")
    suspend fun proposal(id: String): EvolutionProposalEntity?

    @Query(
        "SELECT * FROM evolution_proposals WHERE moduleId = :moduleId AND status = 'INSTALLED' " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun installedProposalForModule(moduleId: String): EvolutionProposalEntity?

    @Upsert
    suspend fun upsertProposal(entity: EvolutionProposalEntity)

    @Query("SELECT * FROM evolution_module_versions WHERE moduleId = :moduleId ORDER BY installedAt ASC")
    suspend fun versions(moduleId: String): List<EvolutionModuleVersionEntity>

    @Upsert
    suspend fun upsertVersion(entity: EvolutionModuleVersionEntity)

    /** Marks [versionId] as the live version of [moduleId] and every other version not live; null clears all. */
    @Query(
        "UPDATE evolution_module_versions SET active = CASE WHEN id = :versionId THEN 1 ELSE 0 END " +
            "WHERE moduleId = :moduleId",
    )
    suspend fun markActive(moduleId: String, versionId: String?)

    @Query(
        "SELECT title AS tool, success, detail, timestamp FROM activity_ledger " +
            "WHERE kindName = 'TOOL_CALL' AND timestamp >= :since ORDER BY timestamp DESC LIMIT :limit",
    )
    suspend fun toolCallsSince(since: Long, limit: Int): List<EvolutionToolCallRow>

    @Query(
        "SELECT role, content, conversation_id AS conversationId, timestamp FROM messages " +
            "WHERE timestamp >= :since AND role IN ('user', 'assistant') ORDER BY timestamp DESC LIMIT :limit",
    )
    suspend fun chatSince(since: Long, limit: Int): List<EvolutionChatRow>

    @Query(
        "SELECT skillName AS name, COUNT(*) AS count FROM skill_revisions " +
            "WHERE replacedAt >= :since GROUP BY skillName",
    )
    suspend fun skillChurnSince(since: Long): List<EvolutionSkillChurnRow>
}
