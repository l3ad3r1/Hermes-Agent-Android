package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.ConnectorDao
import com.hermes.agent.data.local.entity.ConnectorEntity
import com.hermes.agent.domain.model.Connector
import com.hermes.agent.domain.model.ConnectorType
import com.hermes.agent.domain.repository.ConnectorRepository
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectorRepositoryImpl @Inject constructor(
    private val dao: ConnectorDao,
) : ConnectorRepository {

    override fun observe(): Flow<List<Connector>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun add(name: String, type: ConnectorType, config: Map<String, String>): Connector {
        val connector = Connector(
            id = IdGenerator.newId(),
            name = name,
            type = type,
            config = config,
        )
        dao.upsert(ConnectorEntity.fromDomain(connector))
        return connector
    }

    override suspend fun toggle(id: String) = dao.toggleEnabled(id)

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun recordUsed(id: String) = dao.updateLastUsed(id, System.currentTimeMillis())

    override suspend fun getEnabled(): List<Connector> =
        dao.getEnabled().map { it.toDomain() }
}
