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

    @Query("DELETE FROM skills WHERE id = :id AND isBuiltIn = 0")
    suspend fun delete(id: String)

    @Query("DELETE FROM skills WHERE isBuiltIn = 1")
    suspend fun deleteAllBuiltIn()
}
