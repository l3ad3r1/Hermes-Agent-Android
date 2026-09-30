package com.hermes.agent.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.data.evolution.EvolutionSettings
import com.hermes.agent.data.plugin.evolution.EvolutionDispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Runs the builder/reviewer loop for one approved proposal. A worker rather than
 * a ViewModel coroutine because a round can take many minutes on a local LLM and
 * must survive the user leaving the screen. If the process dies mid-run the
 * proposal is left BUILDING/IN_REVIEW and the next dispatch restarts it.
 *
 * Always [Result.success]: the dispatcher records every outcome on the proposal.
 */
@HiltWorker
class EvolutionDispatchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dispatcher: EvolutionDispatcher,
    private val settings: EvolutionSettings,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_PROPOSAL_ID) ?: return Result.success()
        val s = settings.current()
        try {
            val outcome = dispatcher.dispatch(id, s.builderProfile, s.reviewerProfile)
            Timber.tag("EvolutionDispatch").i("proposal %s: %s", id, outcome::class.simpleName)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag("EvolutionDispatch").w(t, "dispatch failed for %s", id)
        }
        return Result.success()
    }

    companion object {
        const val KEY_PROPOSAL_ID = "proposal_id"
        fun uniqueName(proposalId: String) = "hermes.feature_evolution.dispatch.$proposalId"
    }
}
