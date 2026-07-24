package com.hermes.agent.data.repository

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hermes.agent.data.local.dao.AgentTaskDao
import com.hermes.agent.data.local.entity.AgentTaskEntity
import com.hermes.agent.domain.model.AgentTask
import com.hermes.agent.domain.repository.AgentTaskRepository
import com.hermes.agent.util.IdGenerator
import com.hermes.agent.work.AgentTaskWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentTaskRepositoryImpl @Inject constructor(
    private val dao: AgentTaskDao,
    private val workManager: WorkManager,
) : AgentTaskRepository {

    override fun observe(): Flow<List<AgentTask>> =
        dao.observeAll().map { it.map { e -> e.toDomain() } }

    override suspend fun add(label: String, prompt: String): AgentTask {
        val task = AgentTask(id = IdGenerator.newId(), label = label, prompt = prompt)
        // Persist and schedule together (L-005): a task row without its worker
        // is a task that silently never runs.
        dao.upsert(AgentTaskEntity.fromDomain(task))
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInputData(
                workDataOf(
                    AgentTaskWorker.KEY_TASK_ID to task.id,
                    AgentTaskWorker.KEY_TASK_LABEL to task.label,
                    AgentTaskWorker.KEY_TASK_PROMPT to task.prompt,
                ),
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        workManager.enqueueUniqueWork("delegate_${task.id}", ExistingWorkPolicy.KEEP, request)
        return task
    }

    override suspend fun markRunning(id: String) = dao.markRunning(id, System.currentTimeMillis())
    override suspend fun markCompleted(id: String, result: String) = dao.markCompleted(id, result, System.currentTimeMillis())
    override suspend fun markFailed(id: String, reason: String) = dao.markFailed(id, reason, System.currentTimeMillis())
    override suspend fun delete(id: String) = dao.delete(id)
}
