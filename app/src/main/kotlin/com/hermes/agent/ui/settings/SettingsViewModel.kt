package com.hermes.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.backup.GithubBackupService
import com.hermes.agent.data.security.KeystoreManager
import com.hermes.agent.data.security.KnoxSecurityManager
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.data.export.SessionExporter
import com.hermes.agent.data.update.OtaInstaller
import com.hermes.agent.data.update.OtaUpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class UpdateAvailable(
        val version: String,
        /** Direct APK download URL; blank when the release has no APK asset. */
        val apkUrl: String,
        /** Release page — browser fallback when there is no APK asset. */
        val releaseUrl: String,
    ) : UpdateUiState()
    data class Downloading(val version: String, val percent: Int) : UpdateUiState()
    object UpToDate : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

sealed class BackupUiState {
    object Idle : BackupUiState()
    object InProgress : BackupUiState()
    data class Success(val message: String) : BackupUiState()
    data class Error(val message: String) : BackupUiState()
}

sealed class ExportUiState {
    object Idle : ExportUiState()
    object InProgress : ExportUiState()
    /** Export finished; [zipFile] is ready to share. */
    data class Ready(val zipFile: File, val sessionCount: Int, val messageCount: Int) : ExportUiState()
    data class Error(val message: String) : ExportUiState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val knox: KnoxSecurityManager,
    private val keystore: KeystoreManager,
    private val otaUpdateChecker: OtaUpdateChecker,
    private val otaInstaller: OtaInstaller,
    private val githubBackupService: GithubBackupService,
    private val sessionExporter: SessionExporter,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserSettings(),
        )

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _backupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    val isKnoxAvailable: Boolean get() = knox.isKnoxAvailable

    // --- Cloud settings ---

    fun setCloudEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setCloudEnabled(enabled)
    }

    fun setCloudApiKey(key: String) = viewModelScope.launch {
        settingsRepository.setCloudApiKey(key)
    }

    fun setCloudBaseUrl(url: String) = viewModelScope.launch {
        settingsRepository.setCloudBaseUrl(url)
    }

    fun setCloudModel(model: String) = viewModelScope.launch {
        settingsRepository.setCloudModel(model)
    }

    /** Specialised (secondary) cloud model the router uses for simpler tasks. */
    fun setAuxModel(model: String) = viewModelScope.launch {
        settingsRepository.setAuxModel(model)
    }

    /** Optional separate endpoint for the specialist provider (blank = use primary's). */
    fun setAuxBaseUrl(url: String) = viewModelScope.launch {
        settingsRepository.setAuxBaseUrl(url)
    }

    /** Optional separate API key for the specialist provider (blank = use primary's). */
    fun setAuxApiKey(key: String) = viewModelScope.launch {
        settingsRepository.setAuxApiKey(key)
    }

    fun setAppTheme(themeName: String) = viewModelScope.launch {
        settingsRepository.setAppTheme(themeName)
    }

    /** Tool transparency: show tool-call cards live during a turn (default) vs.
     *  keep tool use opaque and show only the final reply. */
    fun setShowToolCalls(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setShowToolCalls(enabled)
    }

    // --- Local API server ---

    /** Persist the enabled flag; auto-generate a bearer key on first enable
     *  so the server is never unintentionally open. Returns nothing — the
     *  caller starts/stops [com.hermes.agent.service.ApiServerService]. */
    fun setApiServerEnabled(enabled: Boolean) = viewModelScope.launch {
        if (enabled && settings.value.apiServerKey.isBlank()) {
            settingsRepository.setApiServerKey(generateApiKey())
        }
        settingsRepository.setApiServerEnabled(enabled)
    }

    fun setApiServerPort(port: Int) = viewModelScope.launch {
        settingsRepository.setApiServerPort(port)
    }

    fun setApiServerAllowLan(allow: Boolean) = viewModelScope.launch {
        settingsRepository.setApiServerAllowLan(allow)
    }

    fun regenerateApiServerKey() = viewModelScope.launch {
        settingsRepository.setApiServerKey(generateApiKey())
    }

    // --- Remote shell (SSH) ---

    fun setSshHost(host: String) = viewModelScope.launch { settingsRepository.setSshHost(host) }
    fun setSshPort(port: Int) = viewModelScope.launch { settingsRepository.setSshPort(port) }
    fun setSshUser(user: String) = viewModelScope.launch { settingsRepository.setSshUser(user) }
    fun setSshPassword(password: String) = viewModelScope.launch { settingsRepository.setSshPassword(password) }

    private fun generateApiKey(): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        return "hermes-" + android.util.Base64.encodeToString(
            bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING,
        )
    }

    fun probeKeystore(onResult: (Boolean) -> Unit) = viewModelScope.launch {
        runCatching {
            keystore.ensureKey(KeystoreManager.ALIAS_CLOUD_API_KEY)
            true
        }.onSuccess(onResult).onFailure { onResult(false) }
    }

    // --- OTA update ---

    fun checkForUpdate() {
        if (_updateState.value is UpdateUiState.Checking) return
        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch {
            val result = runCatching { otaUpdateChecker.check() }
            _updateState.value = when {
                result.isFailure -> UpdateUiState.Error(result.exceptionOrNull()?.message ?: "Check failed")
                result.getOrNull() == null -> UpdateUiState.UpToDate
                else -> {
                    val u = result.getOrNull()!!
                    UpdateUiState.UpdateAvailable(u.version, u.apkUrl, u.releaseUrl)
                }
            }
        }
    }

    /** True when the app may install packages without the user first flipping a setting. */
    fun canInstallPackages(): Boolean = otaInstaller.canInstallPackages()

    /** Opens the system "install unknown apps" screen for this app. */
    fun promptInstallPermission() = otaInstaller.promptInstallPermission()

    /**
     * Downloads the update APK in-app and launches the installer — no browser.
     * Requires the current state to be [UpdateUiState.UpdateAvailable] with an
     * APK asset URL.
     */
    fun downloadAndInstall() {
        val available = _updateState.value as? UpdateUiState.UpdateAvailable ?: return
        if (available.apkUrl.isBlank()) return
        _updateState.value = UpdateUiState.Downloading(available.version, 0)
        viewModelScope.launch {
            val result = otaInstaller.downloadAndInstall(available.apkUrl) { percent ->
                _updateState.value = UpdateUiState.Downloading(available.version, percent)
            }
            _updateState.value = if (result.isFailure) {
                UpdateUiState.Error(result.exceptionOrNull()?.message ?: "Download failed")
            } else {
                // System installer has taken over — reset the panel.
                UpdateUiState.Idle
            }
        }
    }

    fun dismissUpdateState() {
        _updateState.value = UpdateUiState.Idle
    }

    // --- Backup ---

    fun setGithubPat(pat: String) = viewModelScope.launch {
        settingsRepository.setGithubPat(pat)
    }

    /** Lets the user paste a Gist ID manually — needed to restore on a fresh install. */
    fun setGistId(gistId: String) = viewModelScope.launch {
        settingsRepository.setGistId(gistId.trim())
    }

    fun backupNow() {
        if (_backupState.value is BackupUiState.InProgress) return
        _backupState.value = BackupUiState.InProgress
        viewModelScope.launch {
            val s = settings.value
            val result = githubBackupService.backup(s.githubPat, s.gistId.ifBlank { null })
            _backupState.value = when (result) {
                is GithubBackupService.BackupResult.Success -> {
                    settingsRepository.setGistId(result.gistId)
                    settingsRepository.setLastBackupTimestamp(result.timestamp)
                    BackupUiState.Success("Backup saved to GitHub Gist (${result.gistId.take(8)}…)")
                }
                is GithubBackupService.BackupResult.Failure ->
                    BackupUiState.Error(result.message)
            }
        }
    }

    fun restoreBackup() {
        if (_backupState.value is BackupUiState.InProgress) return
        _backupState.value = BackupUiState.InProgress
        viewModelScope.launch {
            val s = settings.value
            val result = githubBackupService.restore(s.githubPat, s.gistId)
            _backupState.value = when (result) {
                is GithubBackupService.RestoreResult.Success -> {
                    val settingsMsg = if (result.settingsRestored) "settings, " else ""
                    BackupUiState.Success(
                        "Restored $settingsMsg${result.memoriesImported} memories, " +
                            "${result.skillsImported} skills, ${result.cronsImported} cron jobs."
                    )
                }
                is GithubBackupService.RestoreResult.Failure ->
                    BackupUiState.Error(result.message)
            }
        }
    }

    fun clearGistId() = viewModelScope.launch {
        settingsRepository.setGistId("")
        settingsRepository.setLastBackupTimestamp(0L)
        _backupState.value = BackupUiState.Idle
    }

    fun dismissBackupState() {
        _backupState.value = BackupUiState.Idle
    }

    // --- Session export (for offline self-evolution) ---

    fun exportSessions() {
        if (_exportState.value is ExportUiState.InProgress) return
        _exportState.value = ExportUiState.InProgress
        viewModelScope.launch {
            val result = runCatching { sessionExporter.exportAll() }
            _exportState.value = result.fold(
                onSuccess = {
                    if (it.sessionCount == 0) {
                        ExportUiState.Error("No conversations to export yet.")
                    } else {
                        ExportUiState.Ready(it.zipFile, it.sessionCount, it.messageCount)
                    }
                },
                onFailure = { ExportUiState.Error(it.message ?: "Export failed") },
            )
        }
    }

    fun dismissExportState() {
        _exportState.value = ExportUiState.Idle
    }
}
