package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.AgentTask
import kotlinx.coroutines.flow.Flow

interface AgentTaskRepository {
    fun observe(): Flow<List<AgentTask>>
    suspend fun add(label: String, prompt: String): AgentTask
    suspend fun markRunning(id: String)
    suspend fun markCompleted(id: String, result: String)
    suspend fun markFailed(id: String, reason: String)
    suspend fun delete(id: String)
}
