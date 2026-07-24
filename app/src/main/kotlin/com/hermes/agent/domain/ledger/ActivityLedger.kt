package com.hermes.agent.domain.ledger

import com.hermes.agent.domain.model.ActivityEntry
import kotlinx.coroutines.flow.Flow

/**
 * The "What Jeeves did" ledger (roadmap v0.13, accountability): every tool
 * execution and delegated background task leaves a persisted trace the user
 * can inspect. Recording must never break the action it records — failures
 * are logged and swallowed by implementations.
 */
interface ActivityLedger {
    suspend fun record(entry: ActivityEntry)
    fun observeRecent(limit: Int = DEFAULT_LIMIT): Flow<List<ActivityEntry>>

    companion object {
        const val DEFAULT_LIMIT = 200
    }
}
