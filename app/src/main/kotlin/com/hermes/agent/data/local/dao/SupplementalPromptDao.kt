package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.PromptRevisionEntity
import com.hermes.agent.data.local.entity.SupplementalPromptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplementalPromptDao {

    @Query("SELECT * FROM supplemental_prompts")
    fun observeAll(): Flow<List<SupplementalPromptEntity>>

    @Query("SELECT * FROM supplemental_prompts")
    suspend fun getAll(): List<SupplementalPromptEntity>

    @Query("SELECT * FROM supplemental_prompts WHERE roleName = :roleName LIMIT 1")
    suspend fun getByRole(roleName: String): SupplementalPromptEntity?

    @Upsert
    suspend fun upsert(prompt: SupplementalPromptEntity)

    @Query("DELETE FROM supplemental_prompts WHERE roleName = :roleName")
    suspend fun deleteByRole(roleName: String)
}

@Dao
interface PromptRevisionDao {

    @Query("SELECT * FROM prompt_revisions WHERE roleName = :roleName ORDER BY replacedAt DESC LIMIT :limit")
    suspend fun getForRole(roleName: String, limit: Int): List<PromptRevisionEntity>

    @Query("SELECT * FROM prompt_revisions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PromptRevisionEntity?

    @Insert
    suspend fun insert(revision: PromptRevisionEntity)

    /** Keep only the newest [keep] revisions for a role. */
    @Query(
        "DELETE FROM prompt_revisions WHERE roleName = :roleName AND id NOT IN (" +
            "SELECT id FROM prompt_revisions WHERE roleName = :roleName " +
            "ORDER BY replacedAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun prune(roleName: String, keep: Int)
}
