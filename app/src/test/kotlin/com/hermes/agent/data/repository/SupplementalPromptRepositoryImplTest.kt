package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.PromptRevisionDao
import com.hermes.agent.data.local.dao.SupplementalPromptDao
import com.hermes.agent.data.local.entity.PromptRevisionEntity
import com.hermes.agent.data.local.entity.SupplementalPromptEntity
import com.hermes.agent.domain.model.AgentRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Archive-on-write and rollback for the continual-harness prompt layer.
 * In-memory DAO fakes rather than mocks — the behaviour under test is
 * stateful, and stubbed call counts would not catch an ordering mistake.
 */
class SupplementalPromptRepositoryImplTest {

    private class FakePromptDao : SupplementalPromptDao {
        val rows = mutableMapOf<String, SupplementalPromptEntity>()
        override fun observeAll(): Flow<List<SupplementalPromptEntity>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<SupplementalPromptEntity> = rows.values.toList()
        override suspend fun getByRole(roleName: String) = rows[roleName]
        override suspend fun upsert(prompt: SupplementalPromptEntity) {
            rows[prompt.roleName] = prompt
        }
        override suspend fun deleteByRole(roleName: String) {
            rows.remove(roleName)
        }
    }

    private class FakeRevisionDao : PromptRevisionDao {
        val rows = mutableListOf<PromptRevisionEntity>()
        override suspend fun getForRole(roleName: String, limit: Int) =
            rows.filter { it.roleName == roleName }.sortedByDescending { it.replacedAt }.take(limit)
        override suspend fun getById(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun insert(revision: PromptRevisionEntity) {
            rows += revision
        }
        override suspend fun prune(roleName: String, keep: Int) {
            val forRole = rows.filter { it.roleName == roleName }.sortedByDescending { it.replacedAt }
            rows.removeAll(forRole.drop(keep).toSet())
        }
    }

    private val promptDao = FakePromptDao()
    private val revisionDao = FakeRevisionDao()
    private val repo = SupplementalPromptRepositoryImpl(promptDao, revisionDao)

    private val role = AgentRole.PRODUCTIVITY

    @Test
    fun `first write archives nothing`() = runTest {
        repo.put(role, "Always confirm the timezone.", "1.0.0")
        assertTrue(revisionDao.rows.isEmpty())
        assertEquals("Always confirm the timezone.", repo.get(role)?.content)
    }

    @Test
    fun `changing content archives the outgoing version`() = runTest {
        repo.put(role, "First notes.", "1.0.0")
        repo.put(role, "Second notes.", "1.0.1", revisionNote = "Refined")

        val revisions = repo.revisions(role)
        assertEquals(1, revisions.size)
        assertEquals("First notes.", revisions.first().content)
        assertEquals("1.0.0", revisions.first().version)
        assertEquals("Refined", revisions.first().note)
        assertEquals("Second notes.", repo.get(role)?.content)
    }

    @Test
    fun `rewriting identical content archives nothing`() = runTest {
        repo.put(role, "Same notes.", "1.0.0")
        repo.put(role, "Same notes.", "1.0.1")
        assertTrue(repo.revisions(role).isEmpty())
    }

    @Test
    fun `clearing archives the cleared text so it can come back`() = runTest {
        repo.put(role, "Valuable notes.", "1.0.0")
        repo.put(role, "", "1.0.0", revisionNote = "Cleared")

        assertEquals("", repo.get(role)?.content)
        val revision = repo.revisions(role).single()
        assertEquals("Valuable notes.", revision.content)

        repo.restore(revision.id)
        assertEquals("Valuable notes.", repo.get(role)?.content)
    }

    @Test
    fun `restore moves the version forward and archives what it replaced`() = runTest {
        repo.put(role, "v1 notes.", "1.0.0")
        repo.put(role, "v2 notes.", "1.0.1")
        val first = repo.revisions(role).single { it.content == "v1 notes." }

        val restored = repo.restore(first.id)

        assertEquals("v1 notes.", restored?.content)
        // Forward, not back to 1.0.0 — history has to stay readable in order.
        assertEquals("1.0.2", restored?.version)
        // The v2 content it displaced is itself archived, so undo is undoable.
        assertTrue(repo.revisions(role).any { it.content == "v2 notes." })
    }

    @Test
    fun `restore of an unknown revision returns null`() = runTest {
        assertNull(repo.restore("does-not-exist"))
    }

    @Test
    fun `roles are isolated from each other`() = runTest {
        repo.put(AgentRole.PRODUCTIVITY, "Productivity notes.", "1.0.0")
        repo.put(AgentRole.RESEARCH, "Research notes.", "1.0.0")

        assertEquals("Productivity notes.", repo.get(AgentRole.PRODUCTIVITY)?.content)
        assertEquals("Research notes.", repo.get(AgentRole.RESEARCH)?.content)
        assertEquals(2, repo.getAll().size)
    }
}
