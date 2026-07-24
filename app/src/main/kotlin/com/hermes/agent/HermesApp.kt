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
import com.hermes.agent.data.log.FileLogTree
import com.hermes.agent.data.log.LogManager
import com.hermes.agent.data.performance.MemoryPressureMonitor
import com.hermes.agent.debug.DebugScreenAwake
import com.hermes.agent.core.settings.HermesSettings
import com.l3ad3r1.octojotter.data.local.ThemePreferences
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider

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

    @Inject
    lateinit var logManager: LogManager

    @Inject
    lateinit var noteIndexerProvider: Provider<com.hermes.agent.data.rag.NoteIndexer>

    @Inject
    lateinit var executionPlanRepositoryProvider: Provider<ExecutionPlanRepository>

    private val applicationScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        DebugScreenAwake.install(this)
        // Capture logs to a file (all build types) so the user can pull them
        // from Settings → Logs; keep the console DebugTree in debug builds.
        Timber.plant(FileLogTree(logManager))
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Constructing NoteIndexer also constructs Jotter's encrypted token store and
        // database. Keep that work off the application injection/startup path so an
        // unavailable optional integration cannot prevent the rest of Jeeves starting.
        applicationScope.launch {
            runCatching { noteIndexerProvider.get().start(applicationScope) }
                .onFailure { Timber.tag("NoteIndexer").w(it, "note indexing unavailable") }
        }
        applicationScope.launch {
            runCatching { executionPlanRepositoryProvider.get().reconcileInterruptedSteps() }
                .onSuccess { count ->
                    if (count > 0) Timber.tag("ExecutionPlan").i("blocked %d interrupted steps", count)
                }
                .onFailure { Timber.tag("ExecutionPlan").w(it, "plan reconciliation unavailable") }
        }

        // Phase 4: start memory pressure polling. If the App Startup
        // initializer already started it via Hilt EntryPoint, this is a
        // no-op; otherwise we start it now that Hilt is initialized.
        memoryPressureMonitor.start()
        migrateLegacyThemeSetting()
        scheduleMemoryConsolidation()
        scheduleSkillImprovement()
        scheduleOtaUpdateCheck()
    }

    /**
     * Jotter's theme used to live in its own `theme_settings` DataStore. It now lives in
     * HermesSettings with everything else, but DataStore has no synchronous read, so — unlike
     * Butler's SharedPreferences — it cannot migrate on first touch. Do it here, off the main
     * thread, before any Activity can observe the theme. It is a no-op once migrated.
     */
    private fun migrateLegacyThemeSetting() {
        CoroutineScope(Dispatchers.IO).launch {
            // Touch the store here first so its one-time SharedPreferences migration (which
            // commit()s) runs off the main thread rather than on whichever caller gets there
            // first — MainActivity reads the theme during composition.
            runCatching { HermesSettings.prefs(this@HermesApp) }
                .onFailure { Timber.tag("Migration").w(it, "settings store warm-up failed") }
            runCatching { ThemePreferences(this@HermesApp).migrateLegacyTheme() }
                .onFailure { Timber.tag("Migration").w(it, "legacy theme migration failed") }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    private fun scheduleOtaUpdateCheck() {
        // JX-01 (docs/UX_AUDIT.md): the OTA checker targets the STANDALONE
        // Hermes-Agent-Android release channel — the wrong channel for this
        // applicationId; "updating" would install a second, separate app.
        // Cancel rather than merely skip: earlier Jeeves builds already enqueued
        // this unique work with ExistingPeriodicWorkPolicy.KEEP, so on updated
        // installs it would otherwise keep running daily forever.
        if (!BuildConfig.OTA_ENABLED) {
            WorkManager.getInstance(this).cancelUniqueWork(OtaUpdateWorker.UNIQUE_NAME)
            return
        }
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
