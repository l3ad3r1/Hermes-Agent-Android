package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hermes.agent.data.local.entity.ScheduledTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTaskDao {

    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    @Query("UPDATE scheduled_tasks SET isEnabled = NOT isEnabled WHERE id = :id")
    suspend fun toggleEnabled(id: String)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE scheduled_tasks SET lastRunAt = :runAt, lastResult = :result WHERE id = :id")
    suspend fun updateLastRun(id: String, runAt: Long, result: String)
}
