package com.hermes.agent.data.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observe(): Flow<UserSettings>
    suspend fun current(): UserSettings

    suspend fun setCloudEnabled(enabled: Boolean)
    suspend fun setCloudApiKey(key: String)
    suspend fun setCloudBaseUrl(url: String)
    suspend fun setCloudModel(model: String)

    suspend fun setAppTheme(themeName: String)
    suspend fun setReasoningEffort(effort: String)
    suspend fun setAuxModel(model: String)
    suspend fun setAuxBaseUrl(url: String)
    suspend fun setAuxApiKey(key: String)

    suspend fun isOnboardingCompleted(): Boolean
    suspend fun setOnboardingCompleted(completed: Boolean)

    // Backup
    suspend fun setGithubPat(pat: String)
    suspend fun setGistId(gistId: String)
    suspend fun setLastBackupTimestamp(ts: Long)
}
