package com.hermes.agent.data.chat

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ReasoningStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = ReasoningStore(context)

    @Test
    fun `saved reasoning comes back for its message and only that one`() = runBlocking {
        store.save("m-1", "  thought about it  ", 6_400)
        store.save("m-2", "   ", 1_000)
        val loaded = store.load(listOf("m-1", "m-2", "m-3"))
        assertEquals(setOf("m-1"), loaded.keys)
        assertEquals(StoredReasoning("thought about it", 6_400), loaded.getValue("m-1"))
    }

    @Test
    fun `a message id cannot climb out of the reasoning folder`() = runBlocking {
        store.save("../../escape", "x", 10)
        assertTrue(File(context.filesDir, "escape.json").exists().not())
        assertEquals(1, store.load(listOf("../../escape")).size)
    }

    @Test
    fun `labels read naturally`() {
        assertEquals("Thought for a moment", StoredReasoning("x", 400).label)
        assertEquals("Thought for 6s", StoredReasoning("x", 6_400).label)
        assertEquals("Thought for 2m 5s", StoredReasoning("x", 125_000).label)
    }
}
