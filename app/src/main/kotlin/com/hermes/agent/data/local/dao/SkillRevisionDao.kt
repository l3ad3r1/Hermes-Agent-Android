package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.hermes.agent.data.local.entity.SkillRevisionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillRevisionDao {

    @Query("SELECT * FROM skill_revisions WHERE skillId = :skillId ORDER BY replacedAt DESC")
    fun observeForSkill(skillId: String): Flow<List<SkillRevisionEntity>>

    @Query("SELECT * FROM skill_revisions WHERE skillId = :skillId ORDER BY replacedAt DESC LIMIT :limit")
    suspend fun getForSkill(skillId: String, limit: Int): List<SkillRevisionEntity>

    @Query("SELECT * FROM skill_revisions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SkillRevisionEntity?

    @Insert
    suspend fun insert(revision: SkillRevisionEntity)

    /**
     * Bound history growth by keeping only the newest [keep] revisions of a
     * skill. Ordering by `replacedAt` then `id` keeps the cut deterministic
     * when two edits land inside the same millisecond — without the tiebreak
     * the LIMIT could keep one row and delete a different one on a re-run.
     */
    @Query(
        "DELETE FROM skill_revisions WHERE skillId = :skillId AND id NOT IN (" +
            "SELECT id FROM skill_revisions WHERE skillId = :skillId " +
            "ORDER BY replacedAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun prune(skillId: String, keep: Int)

    @Query("DELETE FROM skill_revisions WHERE skillId = :skillId")
    suspend fun deleteForSkill(skillId: String)
}
