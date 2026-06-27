package com.hermes.agent.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.model.KanbanTicket
import com.hermes.agent.domain.model.TicketPriority
import com.hermes.agent.domain.repository.KanbanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class KanbanViewMode { BOARD, LIST }

data class KanbanUiState(
    val allTickets: List<KanbanTicket> = emptyList(),
    val filterStatus: KanbanStatus? = null,
    val viewMode: KanbanViewMode = KanbanViewMode.BOARD,
    val showCreateDialog: Boolean = false,
) {
    val ticketsByStatus: Map<KanbanStatus, List<KanbanTicket>>
        get() = allTickets.groupBy { it.status }

    val filteredTickets: List<KanbanTicket>
        get() = (filterStatus?.let { s -> allTickets.filter { it.status == s } } ?: allTickets)
            .sortedByDescending { it.createdAt }
}

/**
 * Room-backed Kanban board. The standalone prototype seeded hard-coded tickets
 * in memory; this version observes [KanbanRepository] so every create / move /
 * delete is durable and shared with the background [com.hermes.agent.service.AgentForegroundService].
 */
@HiltViewModel
class KanbanViewModel @Inject constructor(
    private val repository: KanbanRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(KanbanUiState())
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { repository.seedIfEmpty() }
        viewModelScope.launch {
            repository.observe().collect { tickets ->
                _uiState.value = _uiState.value.copy(allTickets = tickets)
            }
        }
    }

    fun filterByStatus(status: KanbanStatus?) {
        _uiState.value = _uiState.value.copy(filterStatus = status)
    }

    fun toggleViewMode() {
        _uiState.value = _uiState.value.copy(
            viewMode = if (_uiState.value.viewMode == KanbanViewMode.BOARD) {
                KanbanViewMode.LIST
            } else {
                KanbanViewMode.BOARD
            },
        )
    }

    fun showCreateDialog() { _uiState.value = _uiState.value.copy(showCreateDialog = true) }
    fun hideCreateDialog() { _uiState.value = _uiState.value.copy(showCreateDialog = false) }

    fun createTicket(
        title: String,
        body: String,
        priority: TicketPriority,
        tags: List<String>,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.create(title = title.trim(), body = body.trim(), priority = priority, tags = tags)
            hideCreateDialog()
        }
    }

    fun moveTicket(id: String, status: KanbanStatus) {
        viewModelScope.launch {
            if (status == KanbanStatus.DONE) repository.complete(id, result = null)
            else repository.moveTo(id, status)
        }
    }

    fun deleteTicket(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
