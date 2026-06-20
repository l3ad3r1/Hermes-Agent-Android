package com.hermes.agent.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to user settings.
 */
interface SettingsRepository {
    /** Hot stream of the current settings. */
    fun observe(): Flow<UserSettings>

    /** Synchronous-ish snapshot for callers that can't hold a Flow open. */
    suspend fun current(): UserSettings

    suspend fun setOnDeviceEnabled(enabled: Boolean)
    suspend fun setCloudEnabled(enabled: Boolean)
    suspend fun setCloudApiKey(key: String)
    suspend fun setCloudBaseUrl(url: String)
    suspend fun setCloudModel(model: String)
    suspend fun setComplexityThreshold(threshold: Float)
    suspend fun setIdleUnloadMinutes(minutes: Int)

    suspend fun setAppTheme(themeName: String)

    /** Phase 4: true once the user has completed onboarding. */
    suspend fun isOnboardingCompleted(): Boolean
    suspend fun setOnboardingCompleted(completed: Boolean)
}
