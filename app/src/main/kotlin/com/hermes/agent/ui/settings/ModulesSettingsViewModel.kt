package com.hermes.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.plugin.DownloadedPluginArtifact
import com.hermes.agent.domain.plugin.PluginCatalog
import com.hermes.agent.domain.plugin.PluginCatalogEntry
import com.hermes.agent.domain.plugin.PluginModuleDownloadCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModulesUiState(val catalogUrl: String = "", val catalog: PluginCatalog? = null, val loading: Boolean = false, val downloadingId: String? = null, val downloaded: Map<String, DownloadedPluginArtifact> = emptyMap(), val error: String? = null)

@HiltViewModel
class ModulesSettingsViewModel @Inject constructor(private val coordinator: PluginModuleDownloadCoordinator) : ViewModel() {
    private val _state = MutableStateFlow(ModulesUiState())
    val state: StateFlow<ModulesUiState> = _state.asStateFlow()
    fun setCatalogUrl(value: String) = _state.update { it.copy(catalogUrl = value, error = null) }
    fun loadCatalog() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, catalog = null, error = null) }
            coordinator.loadCatalog(state.value.catalogUrl).fold(
                { catalog -> _state.update { it.copy(loading = false, catalog = catalog) } },
                { error -> _state.update { it.copy(loading = false, error = error.message ?: "Could not load the module catalog.") } },
            )
        }
    }
    fun download(entry: PluginCatalogEntry) {
        val key = "${entry.manifest.id}:${entry.manifest.versionCode}"
        viewModelScope.launch {
            _state.update { it.copy(downloadingId = key, error = null) }
            coordinator.download(entry).fold(
                { artifact -> _state.update { it.copy(downloadingId = null, downloaded = it.downloaded + (key to artifact)) } },
                { error -> _state.update { it.copy(downloadingId = null, error = error.message ?: "Could not download the module.") } },
            )
        }
    }
}
