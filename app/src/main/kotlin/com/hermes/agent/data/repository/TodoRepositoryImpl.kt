package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.TodoTaskDao
import com.hermes.agent.data.local.entity.TodoTaskEntity
import com.hermes.agent.domain.model.TaskPriority
import com.hermes.agent.domain.model.TodoTask
import com.hermes.agent.domain.repository.TodoRepository
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TodoRepositoryImpl @Inject constructor(
    private val dao: TodoTaskDao,
) : TodoRepository {

    override fun observeAll(): Flow<List<TodoTask>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeOverdue(): Flow<List<TodoTask>> =
        dao.observeOverdue().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): TodoTask? = dao.getById(id)?.toDomain()

    override suspend fun create(
        title: String,
        body: String,
        priority: TaskPriority,
        dueDateMs: Long?,
        reminderText: String?,
        tags: List<String>,
    ): TodoTask {
        val now = System.currentTimeMillis()
        val task = TodoTask(
            id = "td_" + IdGenerator.newId().take(8),
            title = title,
            body = body,
            priority = priority,
            dueDateMs = dueDateMs,
            reminderText = reminderText,
            tags = tags,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(TodoTaskEntity.fromDomain(task))
        return task
    }

    override suspend fun complete(id: String) {
        val now = System.currentTimeMillis()
        dao.setDone(id, true, now)
    }

    override suspend fun uncomplete(id: String) {
        val now = System.currentTimeMillis()
        dao.setDone(id, false, now)
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun search(query: String, limit: Int): List<TodoTask> =
        dao.search(query, limit).map { it.toDomain() }

    override suspend fun getByTag(tag: String): List<TodoTask> =
        dao.getByTag(tag).map { it.toDomain() }

    override suspend fun reschedule(id: String, dueDateMs: Long?) {
        dao.reschedule(id, dueDateMs, System.currentTimeMillis())
    }

    override suspend fun setPriority(id: String, priority: TaskPriority) {
        dao.setPriority(id, priority.name, System.currentTimeMillis())
    }

    override suspend fun countPending(): Int = dao.countPending()

    override suspend fun countOverdue(): Int = dao.countOverdue()
}
