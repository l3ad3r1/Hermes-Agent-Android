package com.hermes.agent.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.model.Connector
import com.hermes.agent.domain.model.ConnectorType
import com.hermes.agent.domain.repository.ConnectorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val repo: ConnectorRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val connectors: StateFlow<List<Connector>> = repo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val userSettings: StateFlow<UserSettings> = settingsRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    fun add(name: String, type: ConnectorType, config: Map<String, String>) =
        viewModelScope.launch { repo.add(name, type, config) }

    fun toggle(id: String) = viewModelScope.launch { repo.toggle(id) }

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }

    fun setTelegramBotEnabled(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setTelegramBotEnabled(enabled) }

    fun setTelegramBotToken(token: String) =
        viewModelScope.launch { settingsRepository.setTelegramBotToken(token) }

    fun setTelegramAllowedUserIds(ids: String) =
        viewModelScope.launch { settingsRepository.setTelegramAllowedUserIds(ids) }
}
