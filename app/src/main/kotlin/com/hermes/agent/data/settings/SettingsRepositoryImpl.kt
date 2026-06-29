package com.hermes.agent.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val CLOUD_API_KEY = stringPreferencesKey("cloud_api_key")
        val CLOUD_BASE_URL = stringPreferencesKey("cloud_base_url")
        val CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed_v1")
        val APP_THEME = stringPreferencesKey("app_theme")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val AUX_MODEL = stringPreferencesKey("aux_model")
        val AUX_BASE_URL = stringPreferencesKey("aux_base_url")
        val AUX_API_KEY = stringPreferencesKey("aux_api_key")
        val GITHUB_PAT = stringPreferencesKey("github_pat")
        val GIST_ID = stringPreferencesKey("gist_id")
        val LAST_BACKUP_TS = longPreferencesKey("last_backup_ts")
        val TERMUX_HERMES_INSTALLED = booleanPreferencesKey("termux_hermes_installed")
    }

    override fun observe(): Flow<UserSettings> = context.hermesDataStore.data.map { prefs ->
        prefs.toUserSettings()
    }

    override suspend fun current(): UserSettings = observe().first()

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

    override suspend fun setAuxBaseUrl(url: String) {
        context.hermesDataStore.edit { it[Keys.AUX_BASE_URL] = url }
    }

    override suspend fun setAuxApiKey(key: String) {
        context.hermesDataStore.edit { it[Keys.AUX_API_KEY] = key }
    }

    override suspend fun isOnboardingCompleted(): Boolean {
        return context.hermesDataStore.data.map { it[Keys.ONBOARDING_COMPLETED] ?: false }.first()
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        context.hermesDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setGithubPat(pat: String) {
        context.hermesDataStore.edit { it[Keys.GITHUB_PAT] = pat }
    }

    override suspend fun setGistId(gistId: String) {
        context.hermesDataStore.edit { it[Keys.GIST_ID] = gistId }
    }

    override suspend fun setLastBackupTimestamp(ts: Long) {
        context.hermesDataStore.edit { it[Keys.LAST_BACKUP_TS] = ts }
    }

    override suspend fun setTermuxHermesInstalled(installed: Boolean) {
        context.hermesDataStore.edit { it[Keys.TERMUX_HERMES_INSTALLED] = installed }
    }

    private fun Preferences.toUserSettings(): UserSettings {
        return UserSettings(
            cloudEnabled = this[Keys.CLOUD_ENABLED] ?: false,
            cloudApiKey = this[Keys.CLOUD_API_KEY] ?: BuildConfig.CLOUD_API_KEY,
            cloudBaseUrl = this[Keys.CLOUD_BASE_URL] ?: BuildConfig.CLOUD_BASE_URL,
            cloudModel = this[Keys.CLOUD_MODEL] ?: BuildConfig.CLOUD_MODEL,
            appTheme = this[Keys.APP_THEME] ?: "MIDNIGHT",
            reasoningEffort = this[Keys.REASONING_EFFORT] ?: "medium",
            auxModel = this[Keys.AUX_MODEL] ?: "gpt-4o-mini",
            auxBaseUrl = this[Keys.AUX_BASE_URL] ?: "",
            auxApiKey = this[Keys.AUX_API_KEY] ?: "",
            githubPat = this[Keys.GITHUB_PAT] ?: "",
            gistId = this[Keys.GIST_ID] ?: "",
            lastBackupTimestamp = this[Keys.LAST_BACKUP_TS] ?: 0L,
            termuxHermesInstalled = this[Keys.TERMUX_HERMES_INSTALLED] ?: false,
        )
    }
}
