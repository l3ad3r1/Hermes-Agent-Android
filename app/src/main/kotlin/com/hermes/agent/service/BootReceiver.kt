package com.hermes.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hermes.agent.work.AgentBootReconciliationWorker
import timber.log.Timber

internal fun shouldScheduleBootReconciliation(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED

/**
 * Reconciles persisted agent work after credential-protected storage is available.
 *
 * Android forbids starting Hermes' data-sync foreground service from a boot
 * receiver on current releases, so continuous monitoring remains an explicit
 * user action while queued tickets are recovered with WorkManager.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldScheduleBootReconciliation(intent.action)) return

        Timber.i("Boot completed - scheduling Hermes task reconciliation")
        val constraints = androidx.work.Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .build()
        val request = OneTimeWorkRequestBuilder<AgentBootReconciliationWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            AgentBootReconciliationWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )

    }
}
