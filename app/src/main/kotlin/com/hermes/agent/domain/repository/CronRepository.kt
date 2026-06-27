package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.ScheduledTask
import kotlinx.coroutines.flow.Flow

interface CronRepository {
    fun observe(): Flow<List<ScheduledTask>>
    suspend fun add(task: ScheduledTask)
    suspend fun toggle(taskId: String)
    suspend fun delete(taskId: String)
    suspend fun recordRun(taskId: String, result: String)
}
