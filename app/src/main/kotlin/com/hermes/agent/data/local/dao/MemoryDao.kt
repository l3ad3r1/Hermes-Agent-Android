package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hermes.agent.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY created_at DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryEntity): Long

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    /**
     * Phase 1 stand-in for ANN search. Performs a LIKE query on the content
     * column so the UI has something to surface. Phase 2 will replace this
     * with a real SQLite-VSS query against the `embedding` column.
     */
    @Query(
        """
        SELECT * FROM memories
        WHERE content LIKE '%' || :query || '%'
        ORDER BY relevance_score DESC, last_accessed_at DESC
        LIMIT :limit
        """
    )
    suspend fun keywordSearch(query: String, limit: Int): List<MemoryEntity>
}
