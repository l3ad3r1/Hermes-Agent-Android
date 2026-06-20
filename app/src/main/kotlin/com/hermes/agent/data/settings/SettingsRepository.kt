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

    suspend fun isOnboardingCompleted(): Boolean
    suspend fun setOnboardingCompleted(completed: Boolean)
}
