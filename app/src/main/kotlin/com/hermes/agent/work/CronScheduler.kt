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
 * Driven by the cron UI ([com.hermes.agent.ui.cron.CronViewModel]). The schedule lives in
 * WorkManager's own database, separate from the task rows, so a task that is in the list is not
 * necessarily scheduled: a restored or reinstalled app has the rows and none of the work.
 * [reconcile] closes that gap at startup.
 */
@Singleton
class CronScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    fun schedule(task: ScheduledTask) = enqueue(task, ExistingPeriodicWorkPolicy.UPDATE)

    /**
     * Makes sure every enabled task has its periodic work. A task that is already scheduled is
     * left exactly as it is (KEEP), so running this on every launch does not restart anyone's
     * timing; only a task WorkManager has lost gets enqueued again.
     */
    fun reconcile(tasks: List<ScheduledTask>) {
        tasks.filter { it.isEnabled }.forEach { enqueue(it, ExistingPeriodicWorkPolicy.KEEP) }
    }

    private fun enqueue(task: ScheduledTask, policy: ExistingPeriodicWorkPolicy) {
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
            policy,
            request,
        )
    }

    fun cancel(taskId: String) {
        workManager.cancelUniqueWork("cron_$taskId")
    }
}
