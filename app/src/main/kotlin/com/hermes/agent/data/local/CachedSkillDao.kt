package com.hermes.agent.data.local

import com.hermes.agent.data.local.dao.SkillDao
import com.hermes.agent.data.local.entity.SkillEntity
import com.hermes.agent.domain.model.SkillLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory cache in front of [SkillDao] for large skill sets.
 *
 * The skills table is read on hot paths — the per-prompt skill matcher scans
 * every skill, tool calls and refinement workers re-read whole rows — and each
 * read is a full table scan plus per-row decoding, although the table changes
 * rarely. This decorator loads the full list once and serves reads from it.
 *
 * It is wired as the Hilt-provided [SkillDao] singleton, so every consumer
 * (`SkillRepositoryImpl`, `JsonBackupManager` — including backup imports) goes
 * through it, and every write keeps the cache coherent:
 *  - [upsert], [delete] and [deleteAllBuiltIn] drop the cached list; the next
 *    read reloads it (single-flight under a mutex).
 *  - [recordUse], [setLifecycle] and [setPinned] patch the cached row in
 *    place, so the most frequent write — counting a skill use — does not
 *    force a reload of the whole table.
 *  - [observeAll] stays the delegate's live Room flow; UI reactivity is
 *    untouched.
 *
 * Single-row reads ([getByName], [getById]) are served from the cache while
 * it is warm and go straight to the delegate while it is cold — they never
 * trigger the full load themselves.
 */
class CachedSkillDao(private val delegate: SkillDao) : SkillDao {

    private val lock = Mutex()

    @Volatile
    private var cached: List<SkillEntity>? = null

    override fun observeAll(): Flow<List<SkillEntity>> = delegate.observeAll()

    override suspend fun getAll(): List<SkillEntity> =
        cached ?: lock.withLock {
            cached ?: delegate.getAll().also { cached = it }
        }

    override suspend fun getByName(name: String): SkillEntity? {
        val snapshot = cached ?: return delegate.getByName(name)
        return snapshot.firstOrNull { it.name == name }
    }

    override suspend fun getById(id: String): SkillEntity? {
        val snapshot = cached ?: return delegate.getById(id)
        return snapshot.firstOrNull { it.id == id }
    }

    override suspend fun upsert(skill: SkillEntity) = lock.withLock {
        delegate.upsert(skill)
        cached = null
    }

    override suspend fun recordUse(name: String, ts: Long) = lock.withLock {
        delegate.recordUse(name, ts)
        cached = cached?.map { entity ->
            if (entity.name == name) {
                entity.copy(
                    useCount = entity.useCount + 1,
                    lastUsedAt = ts,
                    lifecycleState = SkillLifecycle.ACTIVE.name,
                )
            } else {
                entity
            }
        }
    }

    override suspend fun setLifecycle(id: String, state: String) = lock.withLock {
        delegate.setLifecycle(id, state)
        cached = cached?.map { entity ->
            if (entity.id == id) entity.copy(lifecycleState = state) else entity
        }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) = lock.withLock {
        delegate.setPinned(id, pinned)
        cached = cached?.map { entity ->
            if (entity.id == id) entity.copy(pinned = pinned) else entity
        }
    }

    override suspend fun delete(id: String) = lock.withLock {
        delegate.delete(id)
        cached = null
    }

    override suspend fun deleteAllBuiltIn() = lock.withLock {
        delegate.deleteAllBuiltIn()
        cached = null
    }
}
