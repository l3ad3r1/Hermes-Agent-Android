package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hermes.agent.data.local.entity.PresenceLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PresenceLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PresenceLogEntity)

    @Query("SELECT * FROM presence_logs ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): PresenceLogEntity?

    @Query("SELECT * FROM presence_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<PresenceLogEntity>>

    @Query("SELECT * FROM presence_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<PresenceLogEntity>

    @Query("DELETE FROM presence_logs WHERE timestamp < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long): Int
}
