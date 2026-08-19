package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.KanbanTicketEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KanbanTicketDao {
    @Query("SELECT * FROM kanban_tickets ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<KanbanTicketEntity>>

    @Query("SELECT * FROM kanban_tickets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): KanbanTicketEntity?

    @Query("SELECT * FROM kanban_tickets WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getByStatus(status: String): List<KanbanTicketEntity>

    @Query("SELECT COUNT(*) FROM kanban_tickets WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM kanban_tickets")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(ticket: KanbanTicketEntity)

    @Query("UPDATE kanban_tickets SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    @Query(
        "UPDATE kanban_tickets SET status = :status, result = :result, " +
            "completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun complete(id: String, status: String, result: String?, completedAt: Long, updatedAt: Long)

    @Query("DELETE FROM kanban_tickets WHERE id = :id")
    suspend fun delete(id: String)
}
