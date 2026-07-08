package com.hermes.agent.data.evolution

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.work.SkillRefineWorker
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Event-driven refinement trigger: call [onSkillUsed] right after
 * `SkillRepository.recordUse` and every [REFINE_EVERY_N_USES]-th use of a
 * user-created skill enqueues a one-off [SkillRefineWorker] for it.
 *
 * Cheap by construction: a Room read per use, an LLM call only at the
 * threshold, network-constrained, and `ExistingWorkPolicy.KEEP` so rapid
 * repeat uses can't stack duplicate jobs.
 */
@Singleton
class SkillRefineScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val skillRepository: SkillRepository,
) {

    suspend fun onSkillUsed(skillName: String) {
        val skill = runCatching { skillRepository.getByName(skillName) }.getOrNull() ?: return
        if (skill.isBuiltIn) return
        if (skill.useCount <= 0 || skill.useCount % REFINE_EVERY_N_USES != 0) return

        Timber.tag("SkillRefine").d("'$skillName' hit ${skill.useCount} uses — scheduling refinement")
        val request = OneTimeWorkRequestBuilder<SkillRefineWorker>()
            .setInputData(
                Data.Builder().putString(SkillRefineWorker.KEY_SKILL_NAME, skillName).build(),
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()

        workManager.enqueueUniqueWork(
            SkillRefineWorker.uniqueName(skillName),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        /** Refine a skill on every Nth use — frequent enough to keep hot
         *  skills current, rare enough to keep LLM cost negligible. */
        const val REFINE_EVERY_N_USES = 5
    }
}
