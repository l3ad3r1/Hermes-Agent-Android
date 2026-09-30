package com.hermes.agent.data.evolution

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hermes.agent.work.EvolutionDispatchWorker
import com.hermes.agent.work.FeatureEvolutionWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** WorkManager wiring for feature evolution: the weekly analysis and per-proposal bot runs. */
@Singleton
class FeatureEvolutionScheduler @Inject constructor(
    private val workManager: WorkManager,
) {

    /** Weekly, only while charging, idle and online. Cancelled (not merely skipped) when off. */
    fun applyWeekly(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(FeatureEvolutionWorker.UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<FeatureEvolutionWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            FeatureEvolutionWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Sends one approved proposal to the bots. KEEP: a second tap while it runs does nothing. */
    fun dispatch(proposalId: String) {
        val request = OneTimeWorkRequestBuilder<EvolutionDispatchWorker>()
            .setInputData(Data.Builder().putString(EvolutionDispatchWorker.KEY_PROPOSAL_ID, proposalId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(
            EvolutionDispatchWorker.uniqueName(proposalId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Stops a running bot run for [proposalId]; the worker's cancellation stops the run on the PC too. */
    fun cancelDispatch(proposalId: String) {
        workManager.cancelUniqueWork(EvolutionDispatchWorker.uniqueName(proposalId))
    }
}
