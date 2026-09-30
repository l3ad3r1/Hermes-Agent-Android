package com.hermes.agent.data.evolution

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.data.local.entity.ActivityLedgerEntity
import com.hermes.agent.data.local.entity.ConversationEntity
import com.hermes.agent.data.local.entity.MessageEntity
import com.hermes.agent.data.plugin.evolution.EvolutionModuleVersion
import com.hermes.agent.data.plugin.evolution.EvolutionProposal
import com.hermes.agent.data.plugin.evolution.ProposalKind
import com.hermes.agent.data.plugin.evolution.ProposalStatus
import com.hermes.agent.data.plugin.evolution.transition
import com.hermes.agent.data.tool.ToolRegistryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomEvolutionStoreTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        HermesDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val store = RoomEvolutionStore(db.evolutionDao(), ToolRegistryImpl())

    @After
    fun closeDatabase() = db.close()

    private fun proposal(id: String, status: ProposalStatus = ProposalStatus.PROPOSED, moduleId: String? = null) =
        EvolutionProposal(
            id = id, title = "T $id", problem = "p", evidence = "", kind = ProposalKind.MODULE_FEATURE,
            acceptanceCriteria = listOf("a"), signalKey = "k:$id", status = status, moduleId = moduleId,
            createdAt = 1L, updatedAt = id.hashCode().toLong(),
        )

    @Test
    fun `proposals round-trip and are observed`() = runTest {
        store.insert(proposal("p1"))
        assertEquals(proposal("p1"), store.get("p1"))
        assertEquals(listOf("p1"), store.observe().first().map { it.id })
        assertNull(store.get("missing"))
    }

    @Test
    fun `transitions persist and disallowed ones write nothing`() = runTest {
        store.insert(proposal("p1"))
        assertNull(store.transition("p1", ProposalStatus.INSTALLED, "no"))
        assertEquals(ProposalStatus.PROPOSED, store.get("p1")!!.status)
        store.transition("p1", ProposalStatus.APPROVED, "ok")
        assertEquals(ProposalStatus.APPROVED, store.get("p1")!!.status)
    }

    @Test
    fun `the installed proposal for a module is found`() = runTest {
        store.insert(proposal("p1", ProposalStatus.INSTALLED, moduleId = "evo-a"))
        store.insert(proposal("p2", ProposalStatus.ROLLED_BACK, moduleId = "evo-a"))
        assertEquals("p1", store.findInstalledByModule("evo-a")?.id)
        assertNull(store.findInstalledByModule("evo-b"))
    }

    @Test
    fun `version history keeps one active version, or none`() = runTest {
        fun v(id: String, at: Long) = EvolutionModuleVersion(id, "evo-a", "p1", "1.0.$at", "{}", "ab".repeat(32), setOf("network"), at, false)
        store.record(v("v1", 1))
        store.record(v("v2", 2))
        store.markActive("evo-a", "v2")
        assertEquals(listOf(false, true), store.versions("evo-a").map { it.active })
        store.markActive("evo-a", null)
        assertTrue(store.versions("evo-a").none { it.active })
        assertEquals(setOf("network"), store.versions("evo-a").first().grantedPermissions)
    }

    @Test
    fun `the usage snapshot reads ledger, chat and skill churn`() = runTest {
        val now = System.currentTimeMillis()
        db.activityLedgerDao().insert(ActivityLedgerEntity(0, now, "TOOL_CALL", "interactive", null, "web_search", "HTTP 429", false))
        db.activityLedgerDao().insert(ActivityLedgerEntity(0, now, "DELEGATION", "background", null, "task", "", true))
        db.conversationDao().upsert(ConversationEntity(id = "c1", title = "t", createdAt = now, updatedAt = now))
        db.messageDao().upsert(MessageEntity("m1", "c1", "user", "hello", null, now - 10))
        db.messageDao().upsert(MessageEntity("m2", "c1", "assistant", "hi", "CONVERSATIONAL", now))
        db.messageDao().upsert(MessageEntity("m3", "c1", "tool", "{}", null, now))

        val snapshot = store.load(windowMillis = 60_000)

        assertEquals(listOf("web_search"), snapshot.toolCalls.map { it.tool })
        assertEquals(listOf("user", "assistant"), snapshot.messages.map { it.role })
        assertTrue(snapshot.skillRefinements.isEmpty())
    }
}
