package com.hermes.agent.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupData(
    val schemaVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val memories: List<MemoryBackup> = emptyList(),
    val skills: List<SkillBackup> = emptyList(),
)

@Serializable
data class MemoryBackup(
    val content: String,
    val createdAt: Long,
)

@Serializable
data class SkillBackup(
    val name: String,
    val description: String,
    val content: String,
    val category: String,
    val tags: List<String>,
    val version: String,
    val isBuiltIn: Boolean,
)
