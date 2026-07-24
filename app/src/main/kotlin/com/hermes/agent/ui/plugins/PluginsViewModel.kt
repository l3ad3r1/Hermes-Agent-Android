package com.hermes.agent.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.plugin.PluginInstance
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginRegistry
import com.hermes.agent.domain.plugin.PluginState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PluginsUiState(
    val plugins: List<PluginInstance> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val pluginRegistry: PluginRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PluginsUiState())
    val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadPlugins()
    }

    fun loadPlugins() {
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                pluginRegistry.observePlugins().collect { plugins ->
                    _uiState.value = _uiState.value.copy(plugins = plugins, isLoading = false, error = null)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Failed to load plugins")
            }
        }
    }

    fun activate(id: String) {
        viewModelScope.launch {
            pluginRegistry.activate(id)
        }
    }

    fun suspend_(id: String) {
        viewModelScope.launch {
            pluginRegistry.suspend_(id)
        }
    }

    fun uninstall(id: String) {
        viewModelScope.launch {
            pluginRegistry.uninstall(id)
        }
    }
}
