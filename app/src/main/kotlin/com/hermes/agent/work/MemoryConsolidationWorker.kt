package com.hermes.agent.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.memory.MemoryConsolidator
import com.hermes.agent.domain.model.MessageRole
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Periodic memory-consolidation worker — Section 6.2 of the plan.
 *
 * Runs once per day while the device is charging + idle (constraints
 * set in [com.hermes.agent.HermesApp.scheduleMemoryConsolidation]).
 *
 * Phase 2 body:
 *   1. For each conversation that has had activity since the last
 *      consolidation, extract candidate facts via [MemoryConsolidator].
 *   2. Persist high-quality candidates as long-term memories.
 *   3. Prune the memory store to a bounded size.
 *
 * The worker is fail-soft: any exception is logged and the worker
 * returns Result.success() so WorkManager doesn't retry-storm. The
 * next periodic run will pick up where this one left off.
 */
@HiltWorker
class MemoryConsolidationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val consolidator: MemoryConsolidator,
    private val habitExtractor: com.hermes.agent.domain.agent.HabitExtractor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Timber.tag("MemoryWorker").i("MemoryConsolidationWorker started.")

        // Extract habits
        habitExtractor.extractAndStoreHabits()

        var totalPersisted = 0
        try {
            // Take a snapshot of all conversation ids. observeAll() is a
            // hot flow; we read its first emission.
            val convos = conversationDao.observeAll().firstSnapshot()
            for (conv in convos) {
                val messages = messageDao.recentByConversation(conv.id, limit = 50)
                    .map { entity ->
                        com.hermes.agent.domain.model.Message(
                            id = entity.id,
                            conversationId = entity.conversationId,
                            role = MessageRole.fromWire(entity.role),
                            content = entity.content,
                            agentRole = null,
                            timestamp = entity.timestamp,
                            tokens = entity.tokens,
                            isOnDevice = entity.isOnDevice,
                        )
                    }
                if (messages.isEmpty()) continue
                totalPersisted += consolidator.consolidate(conv.id, messages)
            }
            consolidator.prune(maxCount = 500)
        } catch (t: Throwable) {
            Timber.tag("MemoryWorker").w(t, "consolidation pass failed")
        }

        Timber.tag("MemoryWorker").i(
            "MemoryConsolidationWorker finished. persisted=%d", totalPersisted,
        )
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "hermes.memory_consolidation"
    }
}

/** Helper: take the first emission of a Flow as a suspending snapshot. */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstSnapshot(): T =
    first()
