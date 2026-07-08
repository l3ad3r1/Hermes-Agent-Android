package com.hermes.agent.work

import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
            .putString(ScheduledTaskWorker.KEY_TASK_CRON, task.cronExpression)
            .build()

        // Anchor the first run on the cron's actual fire time ("daily at 8am"
        // used to mean "every 24h from whenever the job was created" — audit
        // M2). WorkManager may still flex within its execution window, and
        // day-of-week filters (weekdays) are enforced at runtime by the
        // worker via CronTiming.shouldRunNow.
        val request = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(
            CronTiming.periodMinutes(task.cronExpression), TimeUnit.MINUTES,
        )
            .setInputData(data)
            .setInitialDelay(CronTiming.initialDelayMillis(task.cronExpression), TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "cron_${task.id}",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(taskId: String) {
        workManager.cancelUniqueWork("cron_$taskId")
    }
}
