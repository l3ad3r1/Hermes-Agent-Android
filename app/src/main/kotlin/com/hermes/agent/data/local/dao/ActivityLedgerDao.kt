package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.hermes.agent.data.local.entity.ActivityLedgerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityLedgerDao {

    @Insert
    suspend fun insert(entry: ActivityLedgerEntity)

    @Query("SELECT * FROM activity_ledger ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityLedgerEntity>>

    /** Keep the ledger bounded: drop everything older than [cutoffMillis]. */
    @Query("DELETE FROM activity_ledger WHERE timestamp < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long): Int
}
