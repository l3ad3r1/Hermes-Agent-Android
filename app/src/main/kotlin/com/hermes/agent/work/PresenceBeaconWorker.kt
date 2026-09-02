package com.hermes.agent.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hermes.agent.data.presence.PresenceManager
import com.hermes.agent.domain.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodically records an ambient presence snapshot so the `presence` tool has
 * something current to report without the agent having to be running.
 *
 * Deliberately independent of [HeartbeatWorker]: presence is a cheap sensor read
 * the agent may want at any time, while the heartbeat is an LLM turn. Coupling
 * them is what left `presence` returning constants — the tool only ever had data
 * when the heartbeat happened to have run.
 *
 * Cadence follows OpenClaw `docs/nodes/presence.md`: infrequent, and it stops
 * entirely when the feature is off.
 */
@HiltWorker
class PresenceBeaconWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val presenceManager: PresenceManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            if (!settingsRepository.current().presenceEnabled) {
                Timber.tag(TAG).d("Presence disabled; skipping beacon.")
                return Result.success()
            }
            presenceManager.captureSnapshot()
            Result.success()
        } catch (t: Throwable) {
            // Fail-silent: an ambient sensor read is never worth a retry storm.
            Timber.tag(TAG).w(t, "Presence beacon failed")
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "presence_beacon_worker"
        private const val TAG = "PresenceBeacon"
        const val INTERVAL_MINUTES = 15L
    }
}

/** Enqueues or cancels the presence beacon to match the current setting. */
@Singleton
class PresenceBeaconScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    fun updateSchedule(enabled: Boolean) {
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<PresenceBeaconWorker>(
                PresenceBeaconWorker.INTERVAL_MINUTES, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES,
            ).build()
            workManager.enqueueUniquePeriodicWork(
                PresenceBeaconWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Timber.tag("PresenceBeacon").i(
                "Scheduled presence beacon every %d minutes",
                PresenceBeaconWorker.INTERVAL_MINUTES,
            )
        } else {
            workManager.cancelUniqueWork(PresenceBeaconWorker.UNIQUE_WORK_NAME)
            Timber.tag("PresenceBeacon").i("Cancelled presence beacon")
        }
    }
}
