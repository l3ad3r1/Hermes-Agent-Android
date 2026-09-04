package com.hermes.agent.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {

    val connectors: StateFlow<List<Connector>> = repo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, type: ConnectorType, config: Map<String, String>) =
        viewModelScope.launch { repo.add(name, type, config) }

    fun toggle(id: String) = viewModelScope.launch { repo.toggle(id) }

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
}
