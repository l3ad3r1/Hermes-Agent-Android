package com.hermes.agent.work

import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hermes.agent.domain.model.CronPresets
import com.hermes.agent.domain.model.ScheduledTask
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues / cancels the periodic WorkManager job backing a [ScheduledTask].
 *
 * Shared by the cron UI ([com.hermes.agent.ui.cron.CronViewModel]) and backup
 * restore ([com.hermes.agent.data.backup.GithubBackupService]) so a restored
 * job is scheduled exactly like one created by hand — otherwise restored crons
 * would sit in the list but never fire.
 */
@Singleton
class CronScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    fun schedule(task: ScheduledTask) {
        val data = Data.Builder()
            .putString(ScheduledTaskWorker.KEY_TASK_ID, task.id)
            .putString(ScheduledTaskWorker.KEY_TASK_PROMPT, task.prompt)
            .putString(ScheduledTaskWorker.KEY_TASK_LABEL, task.label)
            .build()

        val request = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(
            intervalMinutesFor(task.cronExpression), TimeUnit.MINUTES,
        ).setInputData(data).build()

        workManager.enqueueUniquePeriodicWork(
            "cron_${task.id}",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(taskId: String) {
        workManager.cancelUniqueWork("cron_$taskId")
    }

    /** Derive a WorkManager repeat interval from a cron expression.
     *  Full cron parsing is out of scope for WorkManager; we map common
     *  patterns and fall back to 24h for anything else. */
    private fun intervalMinutesFor(cron: String): Long = when (cron.trim()) {
        CronPresets.HOURLY   -> 60L
        CronPresets.WEEKDAYS -> 24 * 60L
        CronPresets.WEEKLY   -> 7 * 24 * 60L
        else                 -> 24 * 60L // daily default
    }
}
