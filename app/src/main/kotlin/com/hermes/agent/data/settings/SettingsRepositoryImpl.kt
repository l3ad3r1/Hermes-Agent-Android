package com.hermes.agent.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.agent.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.hermesDataStore by preferencesDataStore(name = "hermes_settings")

/**
 * DataStore-backed implementation of [SettingsRepository].
 *
 * Defaults are sourced from BuildConfig where applicable (cloud base URL,
 * cloud model, on-device model) so a build can override them via
 * `gradle.properties` without code changes.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val ON_DEVICE_ENABLED = booleanPreferencesKey("on_device_enabled")
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val CLOUD_API_KEY = stringPreferencesKey("cloud_api_key")
        val CLOUD_BASE_URL = stringPreferencesKey("cloud_base_url")
        val CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val ON_DEVICE_MODEL = stringPreferencesKey("on_device_model")
        val COMPLEXITY_THRESHOLD = floatPreferencesKey("complexity_threshold")
        val IDLE_UNLOAD_MINUTES = intPreferencesKey("idle_unload_minutes")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed_v1")
        val APP_THEME = stringPreferencesKey("app_theme")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val AUX_MODEL = stringPreferencesKey("aux_model")
    }

    override fun observe(): Flow<UserSettings> = context.hermesDataStore.data.map { prefs ->
        prefs.toUserSettings()
    }

    override suspend fun current(): UserSettings = observe().first()

    override suspend fun setOnDeviceEnabled(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.ON_DEVICE_ENABLED] = enabled }
    }

    override suspend fun setCloudEnabled(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.CLOUD_ENABLED] = enabled }
    }

    override suspend fun setCloudApiKey(key: String) {
        context.hermesDataStore.edit { it[Keys.CLOUD_API_KEY] = key }
    }

    override suspend fun setCloudBaseUrl(url: String) {
        context.hermesDataStore.edit { it[Keys.CLOUD_BASE_URL] = url }
    }

    override suspend fun setCloudModel(model: String) {
        context.hermesDataStore.edit { it[Keys.CLOUD_MODEL] = model }
    }

    override suspend fun setComplexityThreshold(threshold: Float) {
        context.hermesDataStore.edit { it[Keys.COMPLEXITY_THRESHOLD] = threshold.coerceIn(0f, 1f) }
    }

    override suspend fun setIdleUnloadMinutes(minutes: Int) {
        context.hermesDataStore.edit { it[Keys.IDLE_UNLOAD_MINUTES] = minutes.coerceAtLeast(1) }
    }

    override suspend fun setAppTheme(themeName: String) {
        context.hermesDataStore.edit { it[Keys.APP_THEME] = themeName }
    }

    override suspend fun setReasoningEffort(effort: String) {
        val valid = setOf("minimal", "low", "medium", "high", "xhigh")
        if (effort in valid) context.hermesDataStore.edit { it[Keys.REASONING_EFFORT] = effort }
    }

    override suspend fun setAuxModel(model: String) {
        if (model.isNotBlank()) context.hermesDataStore.edit { it[Keys.AUX_MODEL] = model }
    }

    override suspend fun isOnboardingCompleted(): Boolean {
        return context.hermesDataStore.data.map { it[Keys.ONBOARDING_COMPLETED] ?: false }.first()
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        context.hermesDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    private fun Preferences.toUserSettings(): UserSettings {
        return UserSettings(
            onDeviceEnabled = this[Keys.ON_DEVICE_ENABLED] ?: true,
            cloudEnabled = this[Keys.CLOUD_ENABLED] ?: false,
            cloudApiKey = this[Keys.CLOUD_API_KEY] ?: BuildConfig.CLOUD_API_KEY,
            cloudBaseUrl = this[Keys.CLOUD_BASE_URL] ?: BuildConfig.CLOUD_BASE_URL,
            cloudModel = this[Keys.CLOUD_MODEL] ?: BuildConfig.CLOUD_MODEL,
            onDeviceModel = this[Keys.ON_DEVICE_MODEL] ?: BuildConfig.ON_DEVICE_MODEL,
            complexityThreshold = this[Keys.COMPLEXITY_THRESHOLD] ?: 0.6f,
            idleUnloadMinutes = this[Keys.IDLE_UNLOAD_MINUTES] ?: 5,
            appTheme = this[Keys.APP_THEME] ?: "MIDNIGHT",
            reasoningEffort = this[Keys.REASONING_EFFORT] ?: "medium",
            auxModel = this[Keys.AUX_MODEL] ?: "gpt-4o-mini",
        )
    }
}
