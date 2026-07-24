package com.hermes.agent.data.tools

import com.l3ad3r1.octojotter.data.local.NoteEntity
import com.l3ad3r1.octojotter.data.repository.NoteRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchNotesToolTest {

    private val repository = mockk<NoteRepository>()
    private val tool = SearchNotesTool(repository)

    @Test
    fun `search uses the prompt-safe repository boundary`() = runTest {
        every { repository.searchPromptSafeNotes("roadmap") } returns flowOf(
            listOf(NoteEntity(id = 7, title = "Roadmap", content = "Public plan")),
        )

        val result = tool.execute(mapOf("query" to JsonPrimitive("roadmap")))

        assertTrue(result.errorMessage, result.success)
        assertTrue(result.output.contains("Public plan"))
        verify(exactly = 1) { repository.searchPromptSafeNotes("roadmap") }
        verify(exactly = 0) { repository.searchNotes(any()) }
    }

    @Test
    fun `blank query fails without touching notes`() = runTest {
        val result = tool.execute(mapOf("query" to JsonPrimitive("  ")))

        assertFalse(result.success)
        verify(exactly = 0) { repository.searchPromptSafeNotes(any()) }
    }
}
