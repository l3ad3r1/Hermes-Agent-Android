package com.hermes.agent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hermes.agent.work.MemoryConsolidationWorker
import com.hermes.agent.work.OtaUpdateWorker
import com.hermes.agent.work.SkillImprovementWorker
import com.hermes.agent.data.performance.MemoryPressureMonitor
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Hermes Application entry point.
 *
 * Phase 1 responsibilities:
 *   - Bootstrap Hilt.
 *   - Initialize Timber logging.
 *   - Configure WorkManager with the Hilt-aware WorkerFactory so
 *     [MemoryConsolidationWorker] can inject its dependencies.
 *   - Schedule the periodic memory-consolidation worker (charging + idle
 *     constraint, runs once per day — see Section 5.4 and Section 6.2 of
 *     the plan).
 */
@HiltAndroidApp
class HermesApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var memoryPressureMonitor: MemoryPressureMonitor

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Phase 4: start memory pressure polling. If the App Startup
        // initializer already started it via Hilt EntryPoint, this is a
        // no-op; otherwise we start it now that Hilt is initialized.
        memoryPressureMonitor.start()
        scheduleMemoryConsolidation()
        scheduleSkillImprovement()
        scheduleOtaUpdateCheck()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    private fun scheduleOtaUpdateCheck() {
        val request = PeriodicWorkRequestBuilder<OtaUpdateWorker>(
            1, TimeUnit.DAYS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            OtaUpdateWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun scheduleSkillImprovement() {
        val request = PeriodicWorkRequestBuilder<SkillImprovementWorker>(
            7, TimeUnit.DAYS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SkillImprovementWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun scheduleMemoryConsolidation() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val request = PeriodicWorkRequestBuilder<MemoryConsolidationWorker>(
            1, TimeUnit.DAYS,
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MemoryConsolidationWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
