package com.hermes.agent.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.model.ScheduledTask
import com.hermes.agent.domain.repository.CronRepository
import com.hermes.agent.util.IdGenerator
import com.hermes.agent.work.CronScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CronViewModel @Inject constructor(
    private val cronRepository: CronRepository,
    private val cronScheduler: CronScheduler,
) : ViewModel() {

    val tasks: StateFlow<List<ScheduledTask>> = cronRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTask(label: String, prompt: String, cronExpression: String) {
        val task = ScheduledTask(
            id = IdGenerator.newId(),
            label = label,
            prompt = prompt,
            cronExpression = cronExpression,
        )
        viewModelScope.launch {
            cronRepository.add(task)
            cronScheduler.schedule(task)
        }
    }

    fun toggle(taskId: String) {
        viewModelScope.launch {
            cronRepository.toggle(taskId)
            // Read back from the repository, not the UI StateFlow — `tasks` is
            // WhileSubscribed and may not have emitted yet, which would flip
            // the DB row but silently leave the WorkManager job unchanged.
            val toggled = cronRepository.observe().first().find { it.id == taskId } ?: return@launch
            if (toggled.isEnabled) cronScheduler.schedule(toggled) else cronScheduler.cancel(taskId)
        }
    }

    fun delete(taskId: String) {
        viewModelScope.launch {
            cronRepository.delete(taskId)
            cronScheduler.cancel(taskId)
        }
    }
}
