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
            val task = tasks.value.find { it.id == taskId } ?: return@launch
            val toggled = task.copy(isEnabled = !task.isEnabled)
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
