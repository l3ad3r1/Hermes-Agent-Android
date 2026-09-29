package com.hermes.agent.data.proactive

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hermes.agent.domain.proactive.ProactiveSource
import com.hermes.agent.work.CommitmentNudgeWorker
import com.hermes.agent.work.DailyDigestWorker
import timber.log.Timber
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns proactive consent AND its scheduling as one operation (L-005): a
 * digest consent without its periodic worker is a toggle that silently does
 * nothing. Time-based scheduled tasks keep their own CRON scheduling; this
 * covers the engine-owned sources (digest, nudges).
 */
@Singleton
class ProactiveScheduler @Inject constructor(
    private val store: BudgetStateStore,
    private val workManager: WorkManager,
) {

    fun setConsent(source: ProactiveSource, granted: Boolean) {
        store.setConsent(source, granted)
        schedule(source, granted, ExistingPeriodicWorkPolicy.UPDATE)
    }

    /**
     * Schedules every source whose consent is on; called at start-up. Consent can arrive
     * without its worker (a restored backup brings the preference, not the WorkManager
     * job), and the digest then never ran. KEEP leaves an existing schedule's timing alone.
     */
    fun syncFromConsent() {
        for (source in listOf(ProactiveSource.DIGEST, ProactiveSource.NUDGE)) {
            if (store.consent(source)) schedule(source, true, ExistingPeriodicWorkPolicy.KEEP)
        }
    }

    private fun schedule(source: ProactiveSource, granted: Boolean, policy: ExistingPeriodicWorkPolicy) {
        when (source) {
            ProactiveSource.DIGEST -> reschedule(
                granted,
                DailyDigestWorker.UNIQUE_NAME,
                policy,
            ) {
                PeriodicWorkRequestBuilder<DailyDigestWorker>(Duration.ofDays(1))
                    .setInitialDelay(untilNext(DIGEST_HOUR))
                    .build()
            }
            ProactiveSource.NUDGE -> reschedule(
                granted,
                CommitmentNudgeWorker.UNIQUE_NAME,
                policy,
            ) {
                PeriodicWorkRequestBuilder<CommitmentNudgeWorker>(Duration.ofDays(1))
                    .setInitialDelay(untilNext(NUDGE_HOUR))
                    .build()
            }
            ProactiveSource.SCHEDULED_TASK -> Unit // CRON owns its own scheduling.
        }
    }

    private fun reschedule(
        granted: Boolean,
        uniqueName: String,
        policy: ExistingPeriodicWorkPolicy,
        request: () -> androidx.work.PeriodicWorkRequest,
    ) {
        if (granted) {
            workManager.enqueueUniquePeriodicWork(
                uniqueName,
                policy,
                request(),
            )
            Timber.tag("Proactive").i("scheduled %s", uniqueName)
        } else {
            workManager.cancelUniqueWork(uniqueName)
            Timber.tag("Proactive").i("cancelled %s", uniqueName)
        }
    }

    private fun untilNext(hour: LocalTime): Duration {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    companion object {
        val DIGEST_HOUR: LocalTime = LocalTime.of(8, 0)
        val NUDGE_HOUR: LocalTime = LocalTime.of(17, 0)
    }
}
