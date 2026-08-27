package com.hermes.agent.ui.settings
import com.hermes.agent.domain.llm.*

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.llm.CloudModelCatalog
import com.hermes.agent.data.llm.CloudProviderRegistry
import com.hermes.agent.data.security.KeystoreManager
import com.hermes.agent.data.security.KnoxSecurityManager
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.data.export.SessionExporter
import com.hermes.agent.data.update.OtaInstaller
import com.hermes.agent.data.update.OtaUpdateChecker
import com.hermes.agent.core.settings.HermesSettings
import com.hermes.agent.domain.security.DeviceAuthenticationService
import com.hermes.agent.BuildConfig
import com.hermes.agent.data.export.ImportMode
import com.hermes.agent.data.export.BackupSection
import com.hermes.agent.data.security.CredentialVault
import com.hermes.agent.data.export.JsonBackupManager
import com.hermes.agent.data.backup.LocalBackupManager
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import retrofit2.HttpException
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

sealed class ModelDiscoveryUiState {
    object Idle : ModelDiscoveryUiState()
    object Loading : ModelDiscoveryUiState()
    data class Ready(val models: List<String>) : ModelDiscoveryUiState()
    object Empty : ModelDiscoveryUiState()
    data class Error(val message: String) : ModelDiscoveryUiState()
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
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val knox: KnoxSecurityManager,
    private val keystore: KeystoreManager,
    private val otaUpdateChecker: OtaUpdateChecker,
    private val otaInstaller: OtaInstaller,
    private val sessionExporter: SessionExporter,
    private val cloudModelCatalog: CloudModelCatalog,
    private val localLlmManager: com.hermes.agent.data.llm.LocalLlmManager,
    private val localBackupManager: LocalBackupManager,
    private val jsonBackupManager: JsonBackupManager,
    private val credentialVault: CredentialVault,
    private val deviceAuthenticationService: DeviceAuthenticationService = DeviceAuthenticationService(),
    private val privilegedShellBackend: com.hermes.agent.domain.device.PrivilegedShellBackend,
    private val privilegedShellRetryGate: com.hermes.agent.data.device.PrivilegedShellRetryGate,
    private val oauthManager: com.hermes.agent.data.oauth.OAuthManager,
    private val oauthCallbackReceiver: com.hermes.agent.data.oauth.OAuthCallbackReceiver,
) : ViewModel() {

    // ─── Privileged Shell (Shizuku) ──────────────────────────────────────────
    private val _privilegedStatus = MutableStateFlow(
        com.hermes.agent.domain.device.PrivilegedShellBackend.PrivilegedStatus(
            com.hermes.agent.domain.device.PrivilegedShellBackend.Status.NOT_INSTALLED,
        ),
    )
    val privilegedStatus: StateFlow<com.hermes.agent.domain.device.PrivilegedShellBackend.PrivilegedStatus> =
        _privilegedStatus.asStateFlow()
    val privilegedRetryGateStatus = privilegedShellRetryGate.status

    init {
        refreshPrivilegedStatus()
        viewModelScope.launch {
            oauthCallbackReceiver.events.collect { event ->
                when (event) {
                    is com.hermes.agent.data.oauth.OAuthCallbackEvent.Success -> {
                        handleOAuthSuccess(event.session, event.code)
                    }
                    is com.hermes.agent.data.oauth.OAuthCallbackEvent.Error -> {
                        timber.log.Timber.w("OAuth failed: %s", event.error)
                        event.session?.let { session ->
                            setProviderDiscovery(
                                session.providerId,
                                ModelDiscoveryUiState.Error("Sign in failed: ${event.error}"),
                            )
                        }
                    }
                }
            }
        }
    }

    fun refreshPrivilegedStatus() {
        viewModelScope.launch {
            _privilegedStatus.value = privilegedShellBackend.getStatus()
        }
    }

    fun requestPrivilegedPermission() {
        viewModelScope.launch {
            privilegedShellBackend.requestPermission()
            _privilegedStatus.value = privilegedShellBackend.getStatus()
        }
    }

    fun resetPrivilegedGate() {
        privilegedShellRetryGate.resetGate()
    }

    fun setPrivilegedShellEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPrivilegedShellEnabled(enabled)
            refreshPrivilegedStatus()
        }
    }

    // ─── Appearance settings ────────────────────────────────────────────────

    /** App-wide light/dark/system mode. */
    val themeMode: StateFlow<String> = HermesSettings.themeModeFlow(appContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HermesSettings.THEME_SYSTEM)

    fun setThemeMode(mode: String) = HermesSettings.setThemeMode(appContext, mode)

    val fontFamily: StateFlow<String> = HermesSettings.fontFamilyFlow(appContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HermesSettings.FONT_GEIST)

    val fontScalePercent: StateFlow<Int> = HermesSettings.fontScalePercentFlow(appContext)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HermesSettings.DEFAULT_FONT_SCALE_PERCENT,
        )

    fun setFontFamily(family: String) = HermesSettings.setFontFamily(appContext, family)

    fun setFontScalePercent(percent: Int) = HermesSettings.setFontScalePercent(appContext, percent)

    val settings: StateFlow<UserSettings> = settingsRepository.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserSettings(),
        )

    private val _primaryModelDiscovery = MutableStateFlow<ModelDiscoveryUiState>(ModelDiscoveryUiState.Idle)
    val primaryModelDiscovery: StateFlow<ModelDiscoveryUiState> = _primaryModelDiscovery.asStateFlow()

    private val _specialistModelDiscovery = MutableStateFlow<ModelDiscoveryUiState>(ModelDiscoveryUiState.Idle)
    val specialistModelDiscovery: StateFlow<ModelDiscoveryUiState> = _specialistModelDiscovery.asStateFlow()

    private var modelDiscoveryJob: Job? = null
    private val providerDiscoveryJobs = mutableMapOf<String, Job>()
    private val _providerModelDiscovery = MutableStateFlow<Map<String, ModelDiscoveryUiState>>(emptyMap())
    val providerModelDiscovery: StateFlow<Map<String, ModelDiscoveryUiState>> =
        _providerModelDiscovery.asStateFlow()

    val isModelDownloaded = MutableStateFlow(false)
    val isModelDownloading: StateFlow<Boolean> = localLlmManager.isDownloading
    val modelDownloadProgress: StateFlow<Float> = localLlmManager.downloadProgress
    val modelDownloadError: StateFlow<String> = localLlmManager.downloadError

    /** The list of models offered in the download dropdown. */
    val modelCatalog: List<com.hermes.agent.data.llm.DownloadableModel> =
        com.hermes.agent.data.llm.ModelCatalog.MODELS

    /** Default folder name shown when the user hasn't set a custom directory. */
    val defaultModelDirName: String = com.hermes.agent.data.llm.ModelCatalog.DEFAULT_DIR_NAME

    init {
        viewModelScope.launch {
            repairInvalidProviderBaseUrls()
            migrateLegacyProviderCredential()
        }
        viewModelScope.launch {
            isModelDownloaded.value = localLlmManager.isModelDownloaded()
            localLlmManager.isDownloading.collect { downloading ->
                if (!downloading) {
                    isModelDownloaded.value = localLlmManager.isModelDownloaded()
                }
            }
        }
        scheduleModelDiscovery(delayMillis = 0L)
    }

    /** Re-evaluate whether the selected model exists in the current folder. */
    private fun refreshModelDownloaded() = viewModelScope.launch {
        isModelDownloaded.value = localLlmManager.isModelDownloaded()
    }

    fun downloadLocalModel() {
        viewModelScope.launch { localLlmManager.startDownload() }
    }

    fun cancelModelDownload() = localLlmManager.cancelDownload()

    fun clearModelDownloadError() = localLlmManager.clearDownloadError()

    /** Persist the chosen catalog model; the download check follows the switch. */
    fun setSelectedModelId(id: String) = viewModelScope.launch {
        localLlmManager.setSelectedModelId(id)
        isModelDownloaded.value = localLlmManager.isModelDownloaded()
    }

    /** Persist a custom download directory (blank = default "AI Models"). */
    fun setModelDownloadDir(dir: String) = viewModelScope.launch {
        localLlmManager.setModelDownloadDir(dir.trim())
        isModelDownloaded.value = localLlmManager.isModelDownloaded()
    }

    /** Evaluates device RAM preflight for the current model selection. */
    fun evaluatePreflightForSelectedModel(settings: UserSettings): com.hermes.agent.data.llm.PreflightDecision {
        return if (settings.localModelUri.isNotBlank()) {
            localLlmManager.evaluateCustomModelPreflight(Uri.parse(settings.localModelUri))
        } else {
            val model = com.hermes.agent.data.llm.ModelCatalog.byId(settings.selectedModelId)
            localLlmManager.evaluatePreflight(model)
        }
    }

    /** Whether the app can write models to a user-visible shared folder. */
    fun hasStorageAccess(): Boolean =
        com.hermes.agent.data.llm.LocalLlmManager.hasStorageAccess(appContext)

    /**
     * The Settings screen used to grant All-Files access on Android 11+. Returns
     * null on Android 10, where the UI requests WRITE_EXTERNAL_STORAGE at runtime
     * instead.
     */
    fun allFilesAccessIntent(): android.content.Intent? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${appContext.packageName}"),
            )
        } else null

    /** Re-check permission-dependent state after returning from the grant flow. */
    fun onStorageAccessMaybeChanged() = refreshModelDownloaded()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    // ── Portable JSON export / import ──────────────────────────────────
    //
    // Deliberately separate from the ZIP backup above rather than folded into
    // it: that one is an exact binary image that replaces everything and
    // restarts the app, while this one merges into a live install. Sharing a
    // single progress state would let a running export make the restore button
    // look busy, and the two have genuinely different failure messages.

    private val _jsonBackupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val jsonBackupState: StateFlow<BackupUiState> = _jsonBackupState.asStateFlow()

    fun dismissJsonBackupState() {
        _jsonBackupState.value = BackupUiState.Idle
    }

    /** Writes the export to a location the user picked through the file picker. */
    fun exportJson(uri: Uri, sections: Set<BackupSection>, password: String?) {
        if (_jsonBackupState.value is BackupUiState.InProgress) return
        _jsonBackupState.value = BackupUiState.InProgress
        viewModelScope.launch {
            _jsonBackupState.value = runCatching {
                val backup = jsonBackupManager
                    .export(APP_ID, BuildConfig.VERSION_CODE, sections)
                    .copy(
                        credentials = if (BackupSection.CREDENTIALS in sections) {
                            credentialVault.collect()
                        } else {
                            null
                        },
                    )
                // encode() refuses credentials without a password, so the guard
                // holds even if a screen ever forgets to enforce it.
                val text = jsonBackupManager.encode(backup, password)
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("Could not open the file for writing.")
                Triple(backup.totalItems, backup.credentials != null, !password.isNullOrBlank())
            }.fold(
                onSuccess = { (items, keys, encrypted) ->
                    BackupUiState.Success(
                        buildString {
                            append("Backed up $items item(s)")
                            if (keys) append(", including cloud API keys")
                            append(if (encrypted) ", encrypted." else ".")
                        },
                    )
                },
                onFailure = { BackupUiState.Error(it.message ?: "Backup failed.") },
            )
        }
    }

    fun importJson(uri: Uri, overwrite: Boolean, password: String?) {
        if (_jsonBackupState.value is BackupUiState.InProgress) return
        _jsonBackupState.value = BackupUiState.InProgress
        viewModelScope.launch {
            _jsonBackupState.value = runCatching {
                val text = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: error("Could not open the file for reading.")
                // Decoded before anything is written, so a wrong password or a
                // corrupt file cannot leave the database half-updated.
                val backup = jsonBackupManager.decode(text, password)
                val report = jsonBackupManager.import(
                    backup,
                    if (overwrite) ImportMode.OVERWRITE_EXISTING else ImportMode.SKIP_EXISTING,
                )
                val keys = backup.credentials?.let { credentialVault.apply(it) } ?: 0
                report to keys
            }.fold(
                onSuccess = { (r, keys) ->
                    BackupUiState.Success(
                        "Added ${r.added}, replaced ${r.replaced}, skipped ${r.skipped}." +
                            if (keys > 0) " Restored $keys credential(s)." else "",
                    )
                },
                onFailure = { BackupUiState.Error(it.message ?: "Restore failed.") },
            )
        }
    }

    private val _localBackupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val localBackupState: StateFlow<BackupUiState> = _localBackupState.asStateFlow()

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    val isKnoxAvailable: Boolean get() = knox.isKnoxAvailable

    // --- Cloud model discovery ---

    fun refreshCloudModels() = scheduleModelDiscovery(delayMillis = 0L)

    private fun scheduleModelDiscovery(delayMillis: Long = MODEL_DISCOVERY_DEBOUNCE_MS) {
        modelDiscoveryJob?.cancel()
        modelDiscoveryJob = viewModelScope.launch {
            if (delayMillis > 0L) delay(delayMillis)
            val current = settingsRepository.current()
            if (!current.cloudEnabled) {
                _primaryModelDiscovery.value = ModelDiscoveryUiState.Idle
                _specialistModelDiscovery.value = ModelDiscoveryUiState.Idle
                return@launch
            }

            val primary = CloudEndpoint(current.cloudBaseUrl, current.cloudApiKey)
            val specialist = CloudEndpoint(
                baseUrl = current.auxBaseUrl.ifBlank { primary.baseUrl },
                apiKey = current.auxApiKey.ifBlank { primary.apiKey },
            )

            _primaryModelDiscovery.value = loadingStateFor(primary)
            _specialistModelDiscovery.value = loadingStateFor(specialist)

            val primaryState = discoverModels(primary)
            _primaryModelDiscovery.value = primaryState
            _specialistModelDiscovery.value = if (specialist == primary) {
                primaryState
            } else {
                discoverModels(specialist)
            }
        }
    }

    private fun loadingStateFor(endpoint: CloudEndpoint): ModelDiscoveryUiState =
        if (endpoint.baseUrl.isBlank()) ModelDiscoveryUiState.Idle else ModelDiscoveryUiState.Loading

    private suspend fun discoverModels(endpoint: CloudEndpoint): ModelDiscoveryUiState {
        if (endpoint.baseUrl.isBlank()) return ModelDiscoveryUiState.Idle
        return try {
            val models = cloudModelCatalog.listModels(endpoint.baseUrl, endpoint.apiKey)
            if (models.isEmpty()) ModelDiscoveryUiState.Empty else ModelDiscoveryUiState.Ready(models)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            ModelDiscoveryUiState.Error(modelDiscoveryError(failure))
        }
    }

    private fun modelDiscoveryError(failure: Throwable): String = when (failure) {
        is HttpException -> when (failure.code()) {
            401, 403 -> "The provider rejected model discovery. Check the API key and retry."
            404 -> "This provider does not expose a /models endpoint at that URL. Check the API base URL."
            else -> "The provider returned HTTP ${failure.code()} while loading models."
        }
        else -> failure.message ?: "Couldn't load models from this provider."
    }

    private data class CloudEndpoint(val baseUrl: String, val apiKey: String)

    // --- Cloud settings ---

    fun setCloudEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setCloudEnabled(enabled)
        scheduleModelDiscovery()
    }

    /** Move a recognised legacy custom endpoint into the Desktop-style provider list. */
    private suspend fun migrateLegacyProviderCredential() {
        val current = settingsRepository.current()
        if (current.cloudApiKey.isBlank()) return
        val legacyHost = runCatching { java.net.URI(current.cloudBaseUrl).host }.getOrNull() ?: return
        val definition = CloudProviderRegistry.providers.firstOrNull {
            runCatching { java.net.URI(it.defaultBaseUrl).host }.getOrNull() == legacyHost
        } ?: return
        if (current.cloudProviderProfiles.any { it.id == definition.id }) return
        val migrated = CloudProviderRegistry.profile(definition, current.cloudApiKey).copy(
            baseUrl = current.cloudBaseUrl,
            model = current.cloudModel,
            enabled = current.cloudEnabled,
        )
        settingsRepository.setCloudProviderProfiles(current.cloudProviderProfiles + migrated)
    }

    /** Recover provider URLs damaged by incomplete paste/edit operations. */
    private suspend fun repairInvalidProviderBaseUrls() {
        val current = settingsRepository.current()
        var changed = false
        val repaired = current.cloudProviderProfiles.map { profile ->
            val uri = runCatching { java.net.URI(profile.baseUrl) }.getOrNull()
            val valid = uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()
            val definition = CloudProviderRegistry.definition(profile.id)
            if (!valid && definition != null) {
                changed = true
                profile.copy(baseUrl = definition.defaultBaseUrl)
            } else {
                profile
            }
        }
        if (changed) settingsRepository.setCloudProviderProfiles(repaired)
    }

    fun addProvider(
        definitionId: String,
        customName: String? = null,
        customBaseUrl: String? = null,
        apiKey: String = "",
    ) = viewModelScope.launch {
        val current = settingsRepository.current().cloudProviderProfiles
        val profile = if (definitionId == "custom" || definitionId.startsWith("custom_")) {
            val id = if (definitionId == "custom") "custom_${System.currentTimeMillis()}" else definitionId
            val name = customName?.takeIf { it.isNotBlank() } ?: "Custom Provider"
            val baseUrl = customBaseUrl?.trim()?.ifBlank { "http://localhost:11434/v1" } ?: "http://localhost:11434/v1"
            com.hermes.agent.domain.settings.CloudProviderProfile(
                id = id,
                name = name,
                baseUrl = baseUrl,
                model = "default",
                apiKey = apiKey.trim(),
                enabled = true,
                quality = 0.85,
                cost = 0.05,
                latency = 0.65,
                toolReliability = 0.85,
            )
        } else {
            val definition = CloudProviderRegistry.definition(definitionId) ?: return@launch
            CloudProviderRegistry.profile(definition, apiKey.trim()).copy(
                baseUrl = customBaseUrl?.trim()?.ifBlank { definition.defaultBaseUrl } ?: definition.defaultBaseUrl,
                enabled = apiKey.isNotBlank(),
            )
        }
        settingsRepository.setCloudProviderProfiles(current.filterNot { it.id == profile.id } + profile)
        if (profile.apiKey.isNotBlank() || profile.id.startsWith("custom_")) {
            settingsRepository.setCloudEnabled(true)
            refreshProviderModels(profile.id)
        }
    }

    fun removeProvider(providerId: String) = viewModelScope.launch {
        val current = settingsRepository.current().cloudProviderProfiles
        settingsRepository.setCloudProviderProfiles(current.filterNot { it.id == providerId })
        _providerModelDiscovery.value = _providerModelDiscovery.value - providerId
    }

    fun setProviderApiKey(providerId: String, key: String) = viewModelScope.launch {
        updateProvider(providerId) { it.copy(apiKey = key, enabled = key.isNotBlank()) }
        if (key.isNotBlank()) settingsRepository.setCloudEnabled(true)
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) = viewModelScope.launch {
        updateProvider(providerId) { it.copy(enabled = enabled && (it.apiKey.isNotBlank() || it.id.startsWith("custom_"))) }
    }

    fun setProviderBaseUrl(providerId: String, baseUrl: String) = viewModelScope.launch {
        updateProvider(providerId) { it.copy(baseUrl = baseUrl.trim()) }
    }

    fun setProviderModel(providerId: String, model: String) = viewModelScope.launch {
        updateProvider(providerId) { it.copy(model = model.trim(), modelAutoSelected = false) }
    }

    fun refreshProviderModels(providerId: String, debounceMillis: Long = MODEL_DISCOVERY_DEBOUNCE_MS) {
        providerDiscoveryJobs.remove(providerId)?.cancel()
        providerDiscoveryJobs[providerId] = viewModelScope.launch {
            if (debounceMillis > 0) delay(debounceMillis)
            val current = settingsRepository.current()
            val profile = current.cloudProviderProfiles.firstOrNull { it.id == providerId }
                ?: CloudProviderRegistry.definition(providerId)?.let(CloudProviderRegistry::profile)
                ?: return@launch
            if (profile.baseUrl.isBlank()) {
                setProviderDiscovery(providerId, ModelDiscoveryUiState.Idle)
                return@launch
            }
            setProviderDiscovery(providerId, ModelDiscoveryUiState.Loading)
            val state = discoverModels(CloudEndpoint(profile.baseUrl, profile.apiKey))
            if (state is ModelDiscoveryUiState.Ready) {
                val definition = CloudProviderRegistry.definition(providerId)
                val bestModel = CloudProviderRegistry.bestModel(definition, state.models)
                val selectedModel = when {
                    bestModel == null -> profile.model.ifBlank { state.models.firstOrNull().orEmpty() }
                    profile.modelAutoSelected -> bestModel
                    profile.model !in state.models -> bestModel
                    else -> profile.model
                }
                val ordered = CloudProviderRegistry.orderModels(definition, state.models, selectedModel)
                if (ordered.isEmpty()) {
                    setProviderDiscovery(providerId, ModelDiscoveryUiState.Empty)
                    return@launch
                }
                if (profile.model != selectedModel) {
                    updateProvider(providerId) {
                        it.copy(model = selectedModel, modelAutoSelected = true)
                    }
                }
                setProviderDiscovery(providerId, ModelDiscoveryUiState.Ready(ordered))
            } else {
                setProviderDiscovery(providerId, state)
            }
        }
    }

    private fun setProviderDiscovery(providerId: String, state: ModelDiscoveryUiState) {
        _providerModelDiscovery.value = _providerModelDiscovery.value + (providerId to state)
    }

    private suspend fun updateProvider(
        providerId: String,
        transform: (com.hermes.agent.domain.settings.CloudProviderProfile) -> com.hermes.agent.domain.settings.CloudProviderProfile,
    ) {
        val current = settingsRepository.current().cloudProviderProfiles
        val existing = current.firstOrNull { it.id == providerId }
            ?: CloudProviderRegistry.definition(providerId)?.let(CloudProviderRegistry::profile)
            ?: com.hermes.agent.domain.settings.CloudProviderProfile(
                id = providerId,
                name = "Custom Provider",
                baseUrl = "",
                model = "",
                apiKey = "",
                enabled = false,
                quality = 0.85,
                cost = 0.05,
                latency = 0.65,
                toolReliability = 0.85,
            )
        settingsRepository.setCloudProviderProfiles(
            current.filterNot { it.id == providerId } + transform(existing),
        )
    }

    fun startOAuthFlow(providerId: String, context: Context) {
        viewModelScope.launch {
            try {
                val (authUrl, session) = oauthManager.buildAuthorizationUrl(providerId)
                oauthCallbackReceiver.registerPendingSession(session)
                setProviderDiscovery(providerId, ModelDiscoveryUiState.Loading)
                val customTabsIntent = androidx.browser.customtabs.CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                try {
                    customTabsIntent.launchUrl(context, android.net.Uri.parse(authUrl))
                } catch (t: Throwable) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(authUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                }
            } catch (t: Throwable) {
                setProviderDiscovery(providerId, ModelDiscoveryUiState.Error(t.message ?: "Failed to start sign in"))
            }
        }
    }

    private suspend fun handleOAuthSuccess(session: com.hermes.agent.domain.oauth.OAuthSession, code: String) {
        setProviderDiscovery(session.providerId, ModelDiscoveryUiState.Loading)
        val result = oauthManager.exchangeCodeForApiKey(session, code)
        result.onSuccess { exchange ->
            setProviderApiKey(exchange.providerId, exchange.apiKey)
            refreshProviderModels(exchange.providerId, debounceMillis = 0L)
        }.onFailure { t ->
            setProviderDiscovery(session.providerId, ModelDiscoveryUiState.Error(t.message ?: "Key exchange failed"))
        }
    }

    fun setCloudApiKey(key: String) = viewModelScope.launch {
        settingsRepository.setCloudApiKey(key)
        scheduleModelDiscovery()
    }

    fun setCloudBaseUrl(url: String) = viewModelScope.launch {
        settingsRepository.setCloudBaseUrl(url)
        scheduleModelDiscovery()
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
        scheduleModelDiscovery()
    }

    /** Optional separate API key for the specialist provider (blank = use primary's). */
    fun setAuxApiKey(key: String) = viewModelScope.launch {
        settingsRepository.setAuxApiKey(key)
        scheduleModelDiscovery()
    }

    fun setAppTheme(themeName: String) = viewModelScope.launch {
        settingsRepository.setAppTheme(themeName)
    }

    /** Tool transparency: show tool-call cards live during a turn (default) vs.
     *  keep tool use opaque and show only the final reply. */
    fun setShowToolCalls(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setShowToolCalls(enabled)
    }

    fun setAutoApprovePhoneActions(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setAutoApprovePhoneActions(enabled)
    }

    fun setTrustedBackgroundPhoneActions(enabled: Boolean) = viewModelScope.launch {
        if (!enabled) {
            settingsRepository.setTrustedBackgroundPhoneActions(false)
            return@launch
        }
        val authenticated = deviceAuthenticationService.authenticate(
            title = "Enable trusted background actions",
            reason = "Confirm with your fingerprint or phone passcode",
        )
        if (authenticated) settingsRepository.setTrustedBackgroundPhoneActions(true)
    }

    fun setLocalModelUri(uri: String) = viewModelScope.launch {
        localLlmManager.setLocalModelUri(uri)
        isModelDownloaded.value = localLlmManager.isModelDownloaded()
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

    private companion object {
        const val MODEL_DISCOVERY_DEBOUNCE_MS = 600L

        /** Long enough for the restore confirmation to be readable before the relaunch. */
        const val RESTART_NOTICE_MS = 1_200L
    }

    // --- OTA update ---

    fun checkForUpdate() {
        // JX-01: the checker targets the standalone Hermes-Agent-Android channel — wrong
        // application for this build. The Settings UI is hidden behind the same flag.
        if (!com.hermes.agent.BuildConfig.OTA_ENABLED) return
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
        // JX-01: see checkForUpdate — that APK is a different application.
        if (!com.hermes.agent.BuildConfig.OTA_ENABLED) return
        val available = _updateState.value as? UpdateUiState.UpdateAvailable ?: return
        if (available.apkUrl.isBlank()) return
        
        otaInstaller.startDownloadService(available.apkUrl)
        _updateState.value = UpdateUiState.Idle
    }

    fun dismissUpdateState() {
        _updateState.value = UpdateUiState.Idle
    }

    // --- Local On-Device Backup ---

    fun createLocalBackup() {
        if (_localBackupState.value is BackupUiState.InProgress) return
        _localBackupState.value = BackupUiState.InProgress
        viewModelScope.launch {
            val result = localBackupManager.exportToZip()
            val location = result.getOrNull()
            if (location != null) {
                // Report where the file actually landed: the export falls back
                // to app-private storage when MediaStore is unavailable, and a
                // hard-coded Downloads path sent the user hunting for a file
                // that was never written there.
                _localBackupState.value =
                    BackupUiState.Success("Local backup saved to ${location.displayPath}")
            } else {
                _localBackupState.value = BackupUiState.Error(result.exceptionOrNull()?.message ?: "Failed to save backup")
            }
        }
    }

    fun restoreLocalBackup(uri: Uri) {
        if (_localBackupState.value is BackupUiState.InProgress) return
        _localBackupState.value = BackupUiState.InProgress
        viewModelScope.launch {
            val result = localBackupManager.restoreFromZip(uri)
            if (result.isSuccess) {
                // The restart lives here, not inside the manager. It used to kill
                // the process before this line ran, so a restore showed no
                // confirmation — and a rejected archive killed the app just the
                // same, with no way to tell the two apart.
                _localBackupState.value = BackupUiState.Success("Backup restored. Restarting...")
                delay(RESTART_NOTICE_MS)
                localBackupManager.restartApp()
            } else {
                _localBackupState.value = BackupUiState.Error(result.exceptionOrNull()?.message ?: "Failed to restore backup")
            }
        }
    }

    fun dismissLocalBackupState() {
        _localBackupState.value = BackupUiState.Idle
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

/** Recorded in exported files for provenance. */
private const val APP_ID = "hermes"
