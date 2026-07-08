package com.hermes.agent.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.data.evolution.EvolutionNotifier
import com.hermes.agent.data.evolution.ReflectiveSkillRefiner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * One-off, event-driven skill refinement — enqueued by
 * [com.hermes.agent.data.evolution.SkillRefineScheduler] every N uses of a
 * skill, so heavily-used skills evolve continuously instead of waiting for
 * the weekly [SkillImprovementWorker] pass.
 *
 * Same guarantees as everywhere else: trace-grounded, gated (Skills Guard +
 * SkillConstraints), body-only, patch bump, fail-soft. Posts a notification
 * on success so self-modification is never invisible.
 */
@HiltWorker
class SkillRefineWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val refiner: ReflectiveSkillRefiner,
    private val notifier: EvolutionNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val skillName = inputData.getString(KEY_SKILL_NAME) ?: return Result.success()
        Timber.tag("SkillRefine").d("event-driven refine: $skillName")

        runCatching {
            when (val outcome = refiner.refine(skillName)) {
                is ReflectiveSkillRefiner.Outcome.Ready -> {
                    if (outcome.proposal.constraintsPass) {
                        refiner.apply(outcome.proposal)
                        notifier.notifySkillsImproved(listOf(skillName))
                        Timber.tag("SkillRefine").i("refined '$skillName' after use threshold")
                    } else {
                        Timber.tag("SkillRefine").d("'$skillName' proposal failed gates — skipped")
                    }
                }
                is ReflectiveSkillRefiner.Outcome.NoChange ->
                    Timber.tag("SkillRefine").d("'$skillName': ${outcome.reason}")
                is ReflectiveSkillRefiner.Outcome.Failed ->
                    Timber.tag("SkillRefine").d("'$skillName': ${outcome.message}")
            }
        }.onFailure { Timber.tag("SkillRefine").w(it, "refine failed for $skillName") }

        // Always success — this is opportunistic; never retry-storm.
        return Result.success()
    }

    companion object {
        const val KEY_SKILL_NAME = "skill_name"
        fun uniqueName(skillName: String) = "hermes.skill_refine.$skillName"
    }
}
