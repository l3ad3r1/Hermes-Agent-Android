package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hermes.agent.data.local.entity.DocumentChunkEntity

@Dao
interface DocumentChunkDao {

    @Query("SELECT * FROM document_chunks WHERE document_id = :documentId ORDER BY ordinal ASC")
    suspend fun getByDocument(documentId: String): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DocumentChunkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DocumentChunkEntity>)

    @Query("DELETE FROM document_chunks WHERE document_id = :documentId")
    suspend fun deleteByDocument(documentId: String): Int

    @Query("SELECT COUNT(*) FROM document_chunks")
    suspend fun count(): Int
}
