package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.AgentTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AgentTaskEntity>>

    @Upsert
    suspend fun upsert(task: AgentTaskEntity)

    @Query("UPDATE agent_tasks SET statusName = 'RUNNING', startedAt = :at WHERE id = :id")
    suspend fun markRunning(id: String, at: Long)

    @Query("UPDATE agent_tasks SET statusName = 'COMPLETED', result = :result, completedAt = :at WHERE id = :id")
    suspend fun markCompleted(id: String, result: String, at: Long)

    @Query("UPDATE agent_tasks SET statusName = 'FAILED', result = :reason, completedAt = :at WHERE id = :id")
    suspend fun markFailed(id: String, reason: String, at: Long)

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun delete(id: String)
}
