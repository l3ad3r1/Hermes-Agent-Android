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
    fun `sync schedules every enabled task without restarting one that already exists`() {
        scheduler.sync(listOf(task("a"), task("b")))

        // KEEP: a task WorkManager already has keeps its timing; only a lost one is enqueued again.
        verify { workManager.enqueueUniquePeriodicWork("cron_a", ExistingPeriodicWorkPolicy.KEEP, any<PeriodicWorkRequest>()) }
        verify { workManager.enqueueUniquePeriodicWork("cron_b", ExistingPeriodicWorkPolicy.KEEP, any<PeriodicWorkRequest>()) }
    }

    @Test
    fun `sync leaves a disabled task unscheduled`() {
        scheduler.sync(listOf(task("off", enabled = false)))

        verify(exactly = 0) { workManager.enqueueUniquePeriodicWork(any(), any(), any<PeriodicWorkRequest>()) }
    }

    @Test
    fun `sync cancels a task that was disabled or deleted since the last change`() {
        val first = scheduler.sync(listOf(task("keep"), task("off"), task("gone")))

        val second = scheduler.sync(listOf(task("keep"), task("off", enabled = false)), first)

        verify { workManager.cancelUniqueWork("cron_off") }
        verify { workManager.cancelUniqueWork("cron_gone") }
        verify(exactly = 0) { workManager.cancelUniqueWork("cron_keep") }
        org.junit.Assert.assertEquals(setOf("keep"), second)
    }

    @Test
    fun `scheduling a task from the cron screen still replaces its work`() {
        scheduler.schedule(task("a"))

        verify { workManager.enqueueUniquePeriodicWork("cron_a", ExistingPeriodicWorkPolicy.UPDATE, any<PeriodicWorkRequest>()) }
    }
}
