package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.ScheduledTaskDao
import com.hermes.agent.data.local.entity.ScheduledTaskEntity
import com.hermes.agent.domain.model.ScheduledTask
import com.hermes.agent.domain.repository.CronRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CronRepositoryImpl @Inject constructor(
    private val dao: ScheduledTaskDao,
) : CronRepository {

    override fun observe(): Flow<List<ScheduledTask>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun add(task: ScheduledTask) =
        dao.upsert(ScheduledTaskEntity.fromDomain(task))

    override suspend fun toggle(taskId: String) =
        dao.toggleEnabled(taskId)

    override suspend fun delete(taskId: String) =
        dao.delete(taskId)

    override suspend fun recordRun(taskId: String, result: String) =
        dao.updateLastRun(taskId, System.currentTimeMillis(), result)
}
