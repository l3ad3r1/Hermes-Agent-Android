package com.hermes.agent.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.hermes.agent.domain.model.ScheduledTask
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CronSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val scheduler = CronScheduler(workManager)

    private fun task(id: String, enabled: Boolean = true) =
        ScheduledTask(id = id, label = id, prompt = "p", cronExpression = "0 8 * * *", isEnabled = enabled)

    @Test
    fun `reconcile schedules every enabled task without restarting one that already exists`() {
        scheduler.reconcile(listOf(task("a"), task("b")))

        // KEEP: a task WorkManager already has keeps its timing; only a lost one is enqueued again.
        verify { workManager.enqueueUniquePeriodicWork("cron_a", ExistingPeriodicWorkPolicy.KEEP, any<PeriodicWorkRequest>()) }
        verify { workManager.enqueueUniquePeriodicWork("cron_b", ExistingPeriodicWorkPolicy.KEEP, any<PeriodicWorkRequest>()) }
    }

    @Test
    fun `reconcile leaves a disabled task unscheduled`() {
        scheduler.reconcile(listOf(task("off", enabled = false)))

        verify(exactly = 0) { workManager.enqueueUniquePeriodicWork(any(), any(), any<PeriodicWorkRequest>()) }
    }

    @Test
    fun `scheduling a task from the cron screen still replaces its work`() {
        scheduler.schedule(task("a"))

        verify { workManager.enqueueUniquePeriodicWork("cron_a", ExistingPeriodicWorkPolicy.UPDATE, any<PeriodicWorkRequest>()) }
    }
}
