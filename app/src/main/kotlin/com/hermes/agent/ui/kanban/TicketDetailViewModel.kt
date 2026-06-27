package com.hermes.agent.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.model.KanbanTicket
import com.hermes.agent.domain.repository.KanbanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TicketDetailViewModel @Inject constructor(
    private val repository: KanbanRepository,
) : ViewModel() {

    private val _ticket = MutableStateFlow<KanbanTicket?>(null)
    val ticket: StateFlow<KanbanTicket?> = _ticket.asStateFlow()

    fun load(id: String) {
        viewModelScope.launch { _ticket.value = repository.get(id) }
    }

    fun move(status: KanbanStatus) {
        val current = _ticket.value ?: return
        viewModelScope.launch {
            if (status == KanbanStatus.DONE) repository.complete(current.id, current.result)
            else repository.moveTo(current.id, status)
            _ticket.value = repository.get(current.id)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val current = _ticket.value ?: return
        viewModelScope.launch {
            repository.delete(current.id)
            onDeleted()
        }
    }
}
