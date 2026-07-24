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
        while (taskProcessor.processNext()) {
            processed += 1
        }
        Timber.i("Boot reconciliation processed %d queued tickets", processed)
        Result.success()
    } catch (error: Exception) {
        Timber.e(error, "Boot reconciliation failed")
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    companion object {
        const val UNIQUE_NAME = "jeeves.boot.reconciliation"
        private const val MAX_RETRIES = 3
    }
}
