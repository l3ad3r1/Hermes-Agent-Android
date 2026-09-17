package com.hermes.agent.data.local

import com.hermes.agent.data.local.dao.SkillDao
import com.hermes.agent.data.local.entity.SkillEntity
import com.hermes.agent.domain.model.SkillLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedSkillDaoTest {

    @Test
    fun `getAll loads once and then serves from the cache`() = runTest {
        val delegate = FakeSkillDao(listOf(skill("a"), skill("b")))
        val dao = CachedSkillDao(delegate)

        val first = dao.getAll()
        val second = dao.getAll()

        assertEquals(listOf("a", "b"), first.map { it.name })
        assertEquals(listOf("a", "b"), second.map { it.name })
        assertEquals(1, delegate.getAllCalls)
    }

    @Test
    fun `cold single-row reads pass through without warming the cache`() = runTest {
        val delegate = FakeSkillDao(listOf(skill("a")))
        val dao = CachedSkillDao(delegate)

        assertEquals("a", dao.getByName("a")?.name)
        assertEquals("a", dao.getById("a")?.name)

        assertEquals(1, delegate.getByNameCalls)
        assertEquals(1, delegate.getByIdCalls)
        assertEquals(0, delegate.getAllCalls)
    }

    @Test
    fun `warm single-row reads are served from the cache`() = runTest {
        val delegate = FakeSkillDao(listOf(skill("a")))
        val dao = CachedSkillDao(delegate)

        dao.getAll() // warm

        assertEquals("a", dao.getByName("a")?.name)
        assertEquals("a", dao.getById("a")?.name)

        assertEquals(0, delegate.getByNameCalls)
        assertEquals(0, delegate.getByIdCalls)
        assertEquals(1, delegate.getAllCalls)
    }

    @Test
    fun `recordUse patches the cached row in place and revives it`() = runTest {
        val delegate = FakeSkillDao(
            listOf(skill("a", lifecycle = SkillLifecycle.ARCHIVED)),
        )
        val dao = CachedSkillDao(delegate)

        dao.getAll() // warm
        dao.recordUse("a", ts = 1234L)

        val cached = dao.getAll().single()
        assertEquals(1, cached.useCount)
        assertEquals(1234L, cached.lastUsedAt!!)
        assertEquals(SkillLifecycle.ACTIVE.name, cached.lifecycleState)
        assertEquals(1, delegate.recordUseCalls)
        // The in-place patch means no full reload happened.
        assertEquals(1, delegate.getAllCalls)
    }

    @Test
    fun `setPinned and setLifecycle patch the cached row in place`() = runTest {
        val delegate = FakeSkillDao(listOf(skill("a")))
        val dao = CachedSkillDao(delegate)

        dao.getAll() // warm
        dao.setPinned("a", true)
        dao.setLifecycle("a", SkillLifecycle.STALE.name)

        val cached = dao.getAll().single()
        assertTrue(cached.pinned)
        assertEquals(SkillLifecycle.STALE.name, cached.lifecycleState)
        assertEquals(1, delegate.getAllCalls)
    }

    @Test
    fun `upsert invalidates so the next read reloads`() = runTest {
        val delegate = FakeSkillDao(listOf(skill("a")))
        val dao = CachedSkillDao(delegate)

        dao.getAll() // warm
        dao.upsert(skill("b"))

        assertEquals(listOf("a", "b"), dao.getAll().map { it.name }.sorted())
        assertEquals(2, delegate.getAllCalls)
    }

    @Test
    fun `destructive writes invalidate the cache`() = runTest {
        val delegate = FakeSkillDao(listOf(skill("a"), skill("builtin", builtIn = true)))
        val dao = CachedSkillDao(delegate)

        dao.getAll() // warm
        dao.delete("a")
        assertEquals(listOf("builtin"), dao.getAll().map { it.name })
        assertEquals(2, delegate.getAllCalls)

        dao.deleteAllBuiltIn()
        assertEquals(emptyList<String>(), dao.getAll().map { it.name })
        assertEquals(3, delegate.getAllCalls)
    }

    @Test
    fun `concurrent cache misses load the table once`() = runTest {
        val delegate = FakeSkillDao(listOf(skill("a")))
        delegate.getAllDelayMs = 50L
        val dao = CachedSkillDao(delegate)

        val results = coroutineScope {
            listOf(
                async { dao.getAll() },
                async { dao.getAll() },
                async { dao.getAll() },
            ).awaitAll()
        }

        assertEquals(1, delegate.getAllCalls)
        assertEquals(3, results.size)
    }

    @Test
    fun `a failed reload propagates and the next read retries`() = runTest {
        val delegate = FakeSkillDao(listOf(skill("a")))
        val dao = CachedSkillDao(delegate)

        dao.getAll() // warm
        delegate.failGetAll = true
        dao.upsert(skill("b")) // invalidate

        val failure = runCatching { dao.getAll() }
        assertTrue(failure.isFailure)

        delegate.failGetAll = false
        assertEquals(listOf("a", "b"), dao.getAll().map { it.name })
        assertEquals(3, delegate.getAllCalls)
    }

    @Test
    fun `observeAll passes the delegate flow through live`() = runTest {
        val delegate = FakeSkillDao(listOf(skill("a")))
        val dao = CachedSkillDao(delegate)

        val flow = dao.observeAll()
        assertEquals(listOf("a"), flow.first().map { it.name })

        delegate.setAll(listOf(skill("a"), skill("b")))
        assertEquals(listOf("a", "b"), flow.first().map { it.name })
    }
}

private fun skill(
    name: String,
    builtIn: Boolean = false,
    lifecycle: SkillLifecycle = SkillLifecycle.ACTIVE,
) = SkillEntity(
    id = name,
    name = name,
    description = "",
    version = "1.0.0",
    content = "# $name",
    category = "general",
    tagsJson = "[]",
    isBuiltIn = builtIn,
    createdAt = 0L,
    updatedAt = 0L,
    lifecycleState = lifecycle.name,
)

private class FakeSkillDao(initial: List<SkillEntity> = emptyList()) : SkillDao {

    private val rows = MutableStateFlow(initial)

    var getAllCalls = 0
        private set
    var getByNameCalls = 0
        private set
    var getByIdCalls = 0
        private set
    var recordUseCalls = 0
        private set

    var getAllDelayMs = 0L
    var failGetAll = false

    fun setAll(items: List<SkillEntity>) {
        rows.value = items
    }

    override fun observeAll(): Flow<List<SkillEntity>> = rows

    override suspend fun getAll(): List<SkillEntity> {
        getAllCalls++
        if (getAllDelayMs > 0) delay(getAllDelayMs)
        check(!failGetAll) { "delegate read failed" }
        return rows.value
    }

    override suspend fun getByName(name: String): SkillEntity? {
        getByNameCalls++
        return rows.value.firstOrNull { it.name == name }
    }

    override suspend fun getById(id: String): SkillEntity? {
        getByIdCalls++
        return rows.value.firstOrNull { it.id == id }
    }

    override suspend fun upsert(skill: SkillEntity) {
        rows.value = rows.value.filterNot { it.id == skill.id } + skill
    }

    override suspend fun recordUse(name: String, ts: Long) {
        recordUseCalls++
        rows.value = rows.value.map { entity ->
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

    override suspend fun setLifecycle(id: String, state: String) {
        rows.value = rows.value.map { entity ->
            if (entity.id == id) entity.copy(lifecycleState = state) else entity
        }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        rows.value = rows.value.map { entity ->
            if (entity.id == id) entity.copy(pinned = pinned) else entity
        }
    }

    override suspend fun delete(id: String) {
        // Mirrors the DAO SQL: DELETE ... WHERE id = :id AND isBuiltIn = 0.
        rows.value = rows.value.filterNot { it.id == id && !it.isBuiltIn }
    }

    override suspend fun deleteAllBuiltIn() {
        rows.value = rows.value.filterNot { it.isBuiltIn }
    }
}
