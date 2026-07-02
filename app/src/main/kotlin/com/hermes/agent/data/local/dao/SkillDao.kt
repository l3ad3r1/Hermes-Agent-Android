package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.SkillEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY category ASC, name ASC")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY category ASC, name ASC")
    suspend fun getAll(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SkillEntity?

    @Upsert
    suspend fun upsert(skill: SkillEntity)

    /** Record a use: bump the counter, stamp the time, and revive the skill
     *  to ACTIVE (using a STALE/ARCHIVED skill un-shelves it). */
    @Query(
        "UPDATE skills SET useCount = useCount + 1, lastUsedAt = :ts, " +
            "lifecycleState = 'ACTIVE' WHERE name = :name",
    )
    suspend fun recordUse(name: String, ts: Long)

    @Query("UPDATE skills SET lifecycleState = :state WHERE id = :id")
    suspend fun setLifecycle(id: String, state: String)

    @Query("UPDATE skills SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM skills WHERE id = :id AND isBuiltIn = 0")
    suspend fun delete(id: String)

    @Query("DELETE FROM skills WHERE isBuiltIn = 1")
    suspend fun deleteAllBuiltIn()
}
