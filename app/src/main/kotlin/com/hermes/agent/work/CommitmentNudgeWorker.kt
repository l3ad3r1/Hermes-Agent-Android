package com.hermes.agent.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.data.memory.MemoryConsolidator
import com.hermes.agent.data.proactive.ProactiveNotifier
import com.hermes.agent.domain.proactive.ProactiveSource
import com.hermes.agent.domain.repository.MemoryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Commitment nudge (roadmap v0.12): resurfaces the newest commitment the
 * consolidator extracted ("I will / I need to / I promised to …") within the
 * last week, through the proactive gate. Opt-in only —
 * [ProactiveSource.NUDGE] defaults off, and the budget caps repetition.
 */
@HiltWorker
class CommitmentNudgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val memoryRepository: MemoryRepository,
    private val proactiveNotifier: ProactiveNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val cutoff = System.currentTimeMillis() - COMMITMENT_WINDOW_MS
        val commitment = memoryRepository.observeMemories().first()
            .filter { it.content.startsWith(MemoryConsolidator.COMMITMENT_PREFIX) }
            .filter { it.createdAt >= cutoff }
            .maxByOrNull { it.createdAt }
        if (commitment != null) {
            proactiveNotifier.post(
                ProactiveSource.NUDGE,
                "A commitment you made",
                commitment.content.removePrefix(MemoryConsolidator.COMMITMENT_PREFIX),
            )
        } else {
            Timber.tag("Nudge").d("no recent commitments to nudge")
        }
        Result.success()
    } catch (e: Exception) {
        Timber.tag("Nudge").w(e, "nudge scan failed")
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "proactive_commitment_nudge"
        const val COMMITMENT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    }
}
