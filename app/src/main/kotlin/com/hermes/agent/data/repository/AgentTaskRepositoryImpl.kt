package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.AgentTaskDao
import com.hermes.agent.data.local.entity.AgentTaskEntity
import com.hermes.agent.domain.model.AgentTask
import com.hermes.agent.domain.repository.AgentTaskRepository
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentTaskRepositoryImpl @Inject constructor(
    private val dao: AgentTaskDao,
) : AgentTaskRepository {

    override fun observe(): Flow<List<AgentTask>> =
        dao.observeAll().map { it.map { e -> e.toDomain() } }

    override suspend fun add(label: String, prompt: String): AgentTask {
        val task = AgentTask(id = IdGenerator.newId(), label = label, prompt = prompt)
        dao.upsert(AgentTaskEntity.fromDomain(task))
        return task
    }

    override suspend fun markRunning(id: String) = dao.markRunning(id, System.currentTimeMillis())
    override suspend fun markCompleted(id: String, result: String) = dao.markCompleted(id, result, System.currentTimeMillis())
    override suspend fun markFailed(id: String, reason: String) = dao.markFailed(id, reason, System.currentTimeMillis())
    override suspend fun delete(id: String) = dao.delete(id)
}
