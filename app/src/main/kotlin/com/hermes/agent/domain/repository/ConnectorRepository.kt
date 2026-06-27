package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.Connector
import com.hermes.agent.domain.model.ConnectorType
import kotlinx.coroutines.flow.Flow

interface ConnectorRepository {
    fun observe(): Flow<List<Connector>>
    suspend fun add(name: String, type: ConnectorType, config: Map<String, String>): Connector
    suspend fun toggle(id: String)
    suspend fun delete(id: String)
    suspend fun recordUsed(id: String)
    suspend fun getEnabled(): List<Connector>
}
