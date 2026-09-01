package com.hermes.agent.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages periodic scheduling of heartbeat automation turns.
 * Ported from OpenClaw heartbeat node specification.
 */
@Singleton
class HeartbeatScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    fun updateSchedule(enabled: Boolean, intervalMinutes: Int = 30) {
        if (enabled) {
            val clampedInterval = intervalMinutes.coerceIn(15, 1440).toLong()
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                clampedInterval, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES,
            ).build()

            workManager.enqueueUniquePeriodicWork(
                HeartbeatWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Timber.tag("HeartbeatScheduler").i("Scheduled periodic heartbeat every %d minutes", clampedInterval)
        } else {
            workManager.cancelUniqueWork(HeartbeatWorker.UNIQUE_WORK_NAME)
            Timber.tag("HeartbeatScheduler").i("Cancelled periodic heartbeat work")
        }
    }
}
