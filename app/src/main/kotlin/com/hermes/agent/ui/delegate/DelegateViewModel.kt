package com.hermes.agent.ui.delegate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.hermes.agent.domain.model.AgentTask
import com.hermes.agent.domain.repository.AgentTaskRepository
import com.hermes.agent.work.AgentTaskWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DelegateViewModel @Inject constructor(
    private val repo: AgentTaskRepository,
    private val workManager: WorkManager,
) : ViewModel() {

    val tasks: StateFlow<List<AgentTask>> = repo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delegate(label: String, prompt: String) = viewModelScope.launch {
        val task = repo.add(label, prompt)
        val data = workDataOf(
            AgentTaskWorker.KEY_TASK_ID     to task.id,
            AgentTaskWorker.KEY_TASK_LABEL  to task.label,
            AgentTaskWorker.KEY_TASK_PROMPT to task.prompt,
        )
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()
        workManager.enqueueUniqueWork("delegate_${task.id}", ExistingWorkPolicy.KEEP, request)
    }

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
}
