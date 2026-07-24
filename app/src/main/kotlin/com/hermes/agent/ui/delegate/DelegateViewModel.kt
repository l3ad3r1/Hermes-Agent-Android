package com.hermes.agent.ui.delegate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.model.AgentTask
import com.hermes.agent.domain.repository.AgentTaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DelegateViewModel @Inject constructor(
    private val repo: AgentTaskRepository,
) : ViewModel() {

    val tasks: StateFlow<List<AgentTask>> = repo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Persisting the task also schedules its worker (L-005) — see the repository. */
    fun delegate(label: String, prompt: String) = viewModelScope.launch {
        repo.add(label, prompt)
    }

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
}
