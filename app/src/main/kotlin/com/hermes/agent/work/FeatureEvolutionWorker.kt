package com.hermes.agent.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.data.evolution.EvolutionSettings
import com.hermes.agent.data.plugin.evolution.FeatureEvolutionAnalyzer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Weekly usage analysis (charging + idle + network, scheduled by
 * [com.hermes.agent.data.evolution.FeatureEvolutionScheduler] when the user
 * turned it on). Only proposes: every proposal waits for approval.
 *
 * Fail-soft like the other self-improvement workers: always [Result.success],
 * so a missing cloud key or a bad model reply never becomes a retry storm.
 */
@HiltWorker
class FeatureEvolutionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val analyzer: FeatureEvolutionAnalyzer,
    private val settings: EvolutionSettings,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!settings.current().weeklyAnalysis) return Result.success()
        try {
            val outcome = analyzer.analyze(notify = true)
            settings.recordAnalysis(System.currentTimeMillis(), describe(outcome))
            Timber.tag(TAG).i("weekly analysis: %s", describe(outcome))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "weekly analysis failed")
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "hermes.feature_evolution.weekly"
        private const val TAG = "FeatureEvolution"

        fun describe(outcome: FeatureEvolutionAnalyzer.Outcome): String = when (outcome) {
            is FeatureEvolutionAnalyzer.Outcome.Created -> "${outcome.proposals.size} new proposal(s)"
            FeatureEvolutionAnalyzer.Outcome.NoSignals -> "No usage signals yet"
            is FeatureEvolutionAnalyzer.Outcome.NothingNew -> outcome.reason
            is FeatureEvolutionAnalyzer.Outcome.Failed -> "Failed: ${outcome.message}"
        }
    }
}
