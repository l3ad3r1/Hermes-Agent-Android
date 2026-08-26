package com.hermes.agent.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.service.KanbanTaskProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Finite reboot recovery for persisted kanban work.
 *
 * This deliberately does not recreate the always-on foreground service: Android
 * does not permit starting its data-sync service type from BOOT_COMPLETED.
 */
@HiltWorker
class AgentBootReconciliationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val taskProcessor: KanbanTaskProcessor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        var processed = 0
        // Bounded, because reboot recovery must end. A ticket whose processing
        // re-queues it keeps processNext() returning true forever, and this runs
        // at boot where a spinning worker is invisible and burns the battery.
        // Anything still queued is picked up by the normal agent service.
        while (processed < MAX_TICKETS_PER_RUN && taskProcessor.processNext()) {
            processed += 1
        }
        if (processed >= MAX_TICKETS_PER_RUN) {
            Timber.w("Boot reconciliation stopped at the %d-ticket cap", MAX_TICKETS_PER_RUN)
        }
        Timber.i("Boot reconciliation processed %d queued tickets", processed)
        Result.success()
    } catch (error: Exception) {
        Timber.e(error, "Boot reconciliation failed")
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    companion object {
        const val UNIQUE_NAME = "hermes.boot.reconciliation"
        private const val MAX_RETRIES = 3

        /** Upper bound on tickets drained in one reboot-recovery pass. */
        private const val MAX_TICKETS_PER_RUN = 25
    }
}
