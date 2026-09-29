package com.hermes.agent.data.proactive

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.hermes.agent.domain.proactive.ProactiveSource
import com.hermes.agent.work.CommitmentNudgeWorker
import com.hermes.agent.work.DailyDigestWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class ProactiveSchedulerTest {

    @Test
    fun `consent that arrived without its job gets the job at start-up`() {
        // After a full-backup restore consent_DIGEST was true but no digest worker existed.
        val store = mockk<BudgetStateStore> {
            every { consent(ProactiveSource.DIGEST) } returns true
            every { consent(ProactiveSource.NUDGE) } returns false
        }
        val workManager = mockk<WorkManager>(relaxed = true)

        ProactiveScheduler(store, workManager).syncFromConsent()

        verify { workManager.enqueueUniquePeriodicWork(DailyDigestWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, any()) }
        verify(exactly = 0) { workManager.enqueueUniquePeriodicWork(CommitmentNudgeWorker.UNIQUE_NAME, any(), any()) }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }
}
