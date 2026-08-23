package com.hermes.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.local.entity.ScriptPluginEntity
import com.hermes.agent.data.plugin.ScriptPluginRepository
import com.hermes.agent.data.plugin.script.ScriptPluginManifest
import com.hermes.agent.data.plugin.script.ScriptPluginRegistryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A module the user is being asked to approve, with the permissions it wants.
 * Held separately from the browse list so the approval prompt cannot be shown
 * for a manifest that has not been fetched and validated.
 */
data class PendingInstall(
    val manifest: ScriptPluginManifest,
    val sourceUrl: String,
)

data class ModulesUiState(
    val registryUrl: String = ScriptPluginRepository.DEFAULT_REGISTRY_URL,
    val available: List<ScriptPluginRegistryEntry> = emptyList(),
    val installed: List<ScriptPluginEntity> = emptyList(),
    val loading: Boolean = false,
    val busyId: String? = null,
    val pendingInstall: PendingInstall? = null,
    val error: String? = null,
    val message: String? = null,
) {
    val installedIds: Set<String> get() = installed.map { it.id }.toSet()
}

@HiltViewModel
class ModulesSettingsViewModel @Inject constructor(
    private val repository: ScriptPluginRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ModulesUiState())
    val state: StateFlow<ModulesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeInstalled().collect { installed ->
                _state.update { it.copy(installed = installed) }
            }
        }
        loadRegistry()
    }

    fun setRegistryUrl(url: String) = _state.update { it.copy(registryUrl = url) }

    fun loadRegistry() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            repository.fetchRegistry(_state.value.registryUrl)
                .onSuccess { entries ->
                    _state.update { it.copy(loading = false, available = entries) }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(loading = false, error = "Could not load modules: ${t.message}")
                    }
                }
        }
    }

    /** Fetches the manifest and raises the approval prompt; does not install. */
    fun requestInstall(entry: ScriptPluginRegistryEntry) {
        viewModelScope.launch {
            _state.update { it.copy(busyId = entry.id, error = null) }
            repository.fetchManifest(entry)
                .onSuccess { manifest ->
                    _state.update {
                        it.copy(
                            busyId = null,
                            pendingInstall = PendingInstall(manifest, entry.manifestUrl),
                        )
                    }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(busyId = null, error = "Could not read ${entry.name}: ${t.message}")
                    }
                }
        }
    }

    fun cancelInstall() = _state.update { it.copy(pendingInstall = null) }

    /** Installs the pending module. Only reachable after the user approves. */
    fun confirmInstall() {
        val pending = _state.value.pendingInstall ?: return
        viewModelScope.launch {
            _state.update { it.copy(pendingInstall = null, busyId = pending.manifest.id) }
            repository.install(pending.manifest, pending.sourceUrl)
                .onSuccess {
                    _state.update {
                        it.copy(busyId = null, message = "${pending.manifest.name} installed")
                    }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(busyId = null, error = "Install failed: ${t.message}")
                    }
                }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busyId = id) }
            runCatching { repository.setEnabled(id, enabled) }
                .onFailure { t -> _state.update { it.copy(error = t.message) } }
            _state.update { it.copy(busyId = null) }
        }
    }

    fun uninstall(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(busyId = id) }
            runCatching { repository.uninstall(id) }
                .onFailure { t -> _state.update { it.copy(error = t.message) } }
            _state.update { it.copy(busyId = null, message = "Module removed") }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, message = null) }
}
