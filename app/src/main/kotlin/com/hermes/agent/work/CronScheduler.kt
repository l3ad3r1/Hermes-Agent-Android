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
 * The schedule lives in WorkManager's own database, separate from the task rows, so a task that is
 * in the list is not necessarily scheduled. Rows change from several places: the cron screen, the
 * agent's scheduler tool (which only writes the row), a restore. [sync] follows the rows while the
 * app runs, so each of those ends up scheduled.
 */
@Singleton
class CronScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    fun schedule(task: ScheduledTask) = enqueue(task, ExistingPeriodicWorkPolicy.UPDATE)

    /**
     * Brings WorkManager in line with [tasks]. Every enabled task gets its periodic work, and one
     * that already has it is left exactly as it is (KEEP), so running this on every change does not
     * restart anyone's timing. A task in [previouslyScheduled] that is now disabled or deleted is
     * cancelled. Returns the ids scheduled now, to pass back in next time.
     *
     * A routine the agent created used to wait for the next app launch: the tool wrote the row and
     * nothing enqueued it, so "remind me in three minutes" never fired.
     */
    fun sync(tasks: List<ScheduledTask>, previouslyScheduled: Set<String> = emptySet()): Set<String> {
        val enabled = tasks.filter { it.isEnabled }
        enabled.forEach { enqueue(it, ExistingPeriodicWorkPolicy.KEEP) }
        val scheduled = enabled.map { it.id }.toSet()
        (previouslyScheduled - scheduled).forEach(::cancel)
        return scheduled
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
