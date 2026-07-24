package com.hermes.agent.data.repository

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.domain.model.ActivityEntry
import com.hermes.agent.domain.model.ActivityKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityLedgerImplTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        HermesDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val ledger = ActivityLedgerImpl(db.activityLedgerDao())

    @After
    fun closeDatabase() = db.close()

    @Test
    fun `records round-trip newest first`() = runTest {
        val now = System.currentTimeMillis()
        ledger.record(entry(timestamp = now - 1_000, title = "shell", success = false))
        ledger.record(entry(timestamp = now, title = "search_notes", success = true))

        val entries = ledger.observeRecent().first()

        assertEquals(listOf("search_notes", "shell"), entries.map { it.title })
        assertEquals(listOf(true, false), entries.map { it.success })
        assertEquals(ActivityKind.TOOL_CALL, entries.first().kind)
        assertEquals("background", entries.first().origin)
    }

    @Test
    fun `old entries are pruned on write`() = runTest {
        ledger.record(entry(timestamp = 1, title = "ancient", success = true))
        // A fresh write prunes anything past the retention window.
        ledger.record(
            entry(timestamp = System.currentTimeMillis(), title = "recent", success = true),
        )

        val titles = ledger.observeRecent().first().map { it.title }

        assertEquals(listOf("recent"), titles)
    }

    @Test
    fun `observeRecent honours the limit`() = runTest {
        val now = System.currentTimeMillis()
        repeat(5) { i -> ledger.record(entry(timestamp = now + i, title = "t$i", success = true)) }

        val entries = ledger.observeRecent(limit = 3).first()

        assertEquals(3, entries.size)
        assertTrue(entries.first().title == "t4")
    }

    private fun entry(timestamp: Long, title: String, success: Boolean) = ActivityEntry(
        timestamp = timestamp,
        kind = ActivityKind.TOOL_CALL,
        origin = "background",
        conversationId = "c1",
        title = title,
        detail = "detail",
        success = success,
    )
}
