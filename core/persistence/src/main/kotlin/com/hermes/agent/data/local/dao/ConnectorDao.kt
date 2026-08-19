package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.ConnectorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectorDao {
    @Query("SELECT * FROM connectors ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ConnectorEntity>>

    @Query("SELECT * FROM connectors WHERE isEnabled = 1")
    suspend fun getEnabled(): List<ConnectorEntity>

    @Upsert
    suspend fun upsert(connector: ConnectorEntity)

    @Query("UPDATE connectors SET isEnabled = NOT isEnabled WHERE id = :id")
    suspend fun toggleEnabled(id: String)

    @Query("DELETE FROM connectors WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE connectors SET lastUsedAt = :usedAt WHERE id = :id")
    suspend fun updateLastUsed(id: String, usedAt: Long)
}
