package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.ActivityLedgerDao
import com.hermes.agent.data.local.entity.ActivityLedgerEntity
import com.hermes.agent.domain.ledger.ActivityLedger
import com.hermes.agent.domain.model.ActivityEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityLedgerImpl @Inject constructor(
    private val dao: ActivityLedgerDao,
) : ActivityLedger {

    override suspend fun record(entry: ActivityEntry) {
        // The ledger is an audit trail, not a gate: a failed write must never
        // fail the tool call or delegation it records (L-007 — but inverted:
        // the user action here is the tool call, not the bookkeeping).
        runCatching {
            dao.insert(ActivityLedgerEntity.fromDomain(entry))
            dao.pruneOlderThan(System.currentTimeMillis() - RETENTION_MS)
        }.onFailure { Timber.tag("ActivityLedger").w(it, "ledger write failed") }
    }

    override fun observeRecent(limit: Int): Flow<List<ActivityEntry>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    companion object {
        /** 30 days — long enough to audit, bounded enough to stay small. */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
