package com.hermes.agent.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupData(
    val schemaVersion: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val memories: List<MemoryBackup> = emptyList(),
    val skills: List<SkillBackup> = emptyList(),
    // schemaVersion 2+ — null/empty when restoring an older (v1) backup.
    val settings: SettingsBackup? = null,
    val crons: List<CronBackup> = emptyList(),
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

/**
 * Cloud LLM + related app settings.
 *
 * Deliberately excludes the GitHub PAT and Gist ID (those are the restore
 * credentials themselves — restoring them would be circular) and device-local
 * secrets that don't transfer between installs (API-server key, SSH config).
 */
@Serializable
data class SettingsBackup(
    val cloudEnabled: Boolean = false,
    val cloudApiKey: String = "",
    val cloudBaseUrl: String = "",
    val cloudModel: String = "",
    val reasoningEffort: String = "",
    val auxModel: String = "",
    val auxBaseUrl: String = "",
    val auxApiKey: String = "",
    val appTheme: String = "",
)

@Serializable
data class CronBackup(
    val id: String,
    val label: String,
    val prompt: String,
    val cronExpression: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)
