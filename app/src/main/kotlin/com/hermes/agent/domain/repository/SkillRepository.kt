package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.Skill
import kotlinx.coroutines.flow.Flow

interface SkillRepository {
    fun observe(): Flow<List<Skill>>
    suspend fun getAll(): List<Skill>
    suspend fun getByName(name: String): Skill?
    suspend fun upsert(
        name: String,
        description: String,
        content: String,
        category: String = "general",
        tags: List<String> = emptyList(),
        version: String = "1.0.0",
    ): Skill
    suspend fun delete(id: String)
    suspend fun seedBuiltIn()
}
