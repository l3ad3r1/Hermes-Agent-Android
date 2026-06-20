package com.hermes.agent.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.plugin.PluginInstance
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginRegistry
import com.hermes.agent.domain.plugin.PluginState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val pluginRegistry: PluginRegistry,
) : ViewModel() {

    val plugins: StateFlow<List<PluginInstance>> = pluginRegistry.observePlugins()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

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
