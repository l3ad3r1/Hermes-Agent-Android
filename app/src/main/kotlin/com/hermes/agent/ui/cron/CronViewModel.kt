package com.hermes.agent.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hermes.agent.domain.model.ScheduledTask
import com.hermes.agent.domain.model.TaskSchedule
import com.hermes.agent.domain.repository.CronRepository
import com.hermes.agent.util.IdGenerator
import com.hermes.agent.work.ScheduledTaskWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class CronViewModel @Inject constructor(
    private val cronRepository: CronRepository,
    private val workManager: WorkManager,
) : ViewModel() {

    val tasks: StateFlow<List<ScheduledTask>> = cronRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTask(label: String, prompt: String, schedule: TaskSchedule) {
        val task = ScheduledTask(
            id = IdGenerator.newId(),
            label = label,
            prompt = prompt,
            schedule = schedule,
        )
        viewModelScope.launch {
            cronRepository.add(task)
            scheduleWork(task)
        }
    }

    fun toggle(taskId: String) {
        viewModelScope.launch {
            cronRepository.toggle(taskId)
            val task = tasks.value.find { it.id == taskId } ?: return@launch
            val toggled = task.copy(isEnabled = !task.isEnabled)
            if (toggled.isEnabled) scheduleWork(toggled) else cancelWork(taskId)
        }
    }

    fun delete(taskId: String) {
        viewModelScope.launch {
            cronRepository.delete(taskId)
            cancelWork(taskId)
        }
    }

    private fun scheduleWork(task: ScheduledTask) {
        val data = Data.Builder()
            .putString(ScheduledTaskWorker.KEY_TASK_ID, task.id)
            .putString(ScheduledTaskWorker.KEY_TASK_PROMPT, task.prompt)
            .putString(ScheduledTaskWorker.KEY_TASK_LABEL, task.label)
            .build()

        val request = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(
            task.schedule.intervalMinutes, TimeUnit.MINUTES,
        )
            .setInputData(data)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "cron_${task.id}",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun cancelWork(taskId: String) {
        workManager.cancelUniqueWork("cron_$taskId")
    }
}
