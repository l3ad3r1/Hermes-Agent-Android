package com.hermes.agent.data.tools

import com.l3ad3r1.octojotter.data.local.NoteEntity
import com.l3ad3r1.octojotter.data.repository.NoteRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the first agent tool that crosses a feature-module boundary:
 * `create_note` -> Octo Jotter's [NoteRepository].
 */
class CreateNoteToolTest {

    private val repo = mockk<NoteRepository>()
    private val tool = CreateNoteTool(repo)

    private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { it.first to JsonPrimitive(it.second) }

    @Test
    fun `creates a note and reports the new id`() = runTest {
        val captured = slot<NoteEntity>()
        coEvery { repo.insertNote(capture(captured)) } returns 42L

        val result = tool.execute(args("title" to "Groceries", "content" to "- milk\n- eggs"))

        assertTrue(result.errorMessage, result.success)
        assertTrue(result.output.contains("42"))
        assertEquals("Groceries", captured.captured.title)
        assertEquals("- milk\n- eggs", captured.captured.content)
    }

    @Test
    fun `new notes are local-only — they are not queued for gist sync`() = runTest {
        val captured = slot<NoteEntity>()
        coEvery { repo.insertNote(capture(captured)) } returns 1L

        tool.execute(args("title" to "t", "content" to "c"))

        assertFalse("agent-created notes must not auto-push to a Gist", captured.captured.needsSync)
    }

    @Test
    fun `missing title is a tool error, not a crash`() = runTest {
        val result = tool.execute(args("content" to "body only"))
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("title"))
    }

    @Test
    fun `blank title is rejected`() = runTest {
        val result = tool.execute(args("title" to "   ", "content" to "c"))
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("title"))
    }

    @Test
    fun `missing content is a tool error`() = runTest {
        val result = tool.execute(args("title" to "t"))
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("content"))
    }

    @Test
    fun `a repository failure surfaces as a tool error rather than propagating`() = runTest {
        coEvery { repo.insertNote(any()) } throws IllegalStateException("db is locked")

        val result = tool.execute(args("title" to "t", "content" to "c"))

        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("db is locked"))
    }
}
