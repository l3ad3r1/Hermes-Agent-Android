package com.hermes.agent.data.memory

import com.hermes.agent.domain.model.Memory
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.MemoryRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryConsolidatorTest {

    private fun mockRepo(): MemoryRepository = mockk(relaxed = true).also {
        coEvery { it.addMemory(any()) } returns "mem-id"
        coEvery { it.observeMemories() } returns flowOf(emptyList<Memory>())
    }

    @Test
    fun `extracts remember-that facts from user messages`() {
        val consolidator = MemoryConsolidator(mockRepo())
        val messages = listOf(
            Message(
                id = "m1",
                conversationId = "c1",
                role = MessageRole.USER,
                content = "Remember that my favorite color is blue.",
                agentRole = null,
                timestamp = 0,
            ),
            Message(
                id = "m2",
                conversationId = "c1",
                role = MessageRole.ASSISTANT,
                content = "Got it.",
                agentRole = com.hermes.agent.domain.model.AgentRole.DEFAULT,
                timestamp = 0,
            ),
        )
        val candidates = consolidator.extractCandidates(messages)
        assertEquals(1, candidates.size)
        assertTrue(candidates[0].contains("favorite color is blue"))
    }

    @Test
    fun `extracts preference statements`() {
        val consolidator = MemoryConsolidator(mockRepo())
        val messages = listOf(
            Message(
                id = "m1",
                conversationId = "c1",
                role = MessageRole.USER,
                content = "I prefer dark mode in all my editors.",
                agentRole = null,
                timestamp = 0,
            ),
        )
        val candidates = consolidator.extractCandidates(messages)
        assertEquals(1, candidates.size)
        assertTrue(candidates[0].startsWith("User preference:"))
        assertTrue(candidates[0].contains("dark mode"))
    }

    @Test
    fun `ignores assistant messages`() {
        val consolidator = MemoryConsolidator(mockRepo())
        val messages = listOf(
            Message(
                id = "m1",
                conversationId = "c1",
                role = MessageRole.ASSISTANT,
                content = "Remember that the user is busy.",
                agentRole = com.hermes.agent.domain.model.AgentRole.DEFAULT,
                timestamp = 0,
            ),
        )
        val candidates = consolidator.extractCandidates(messages)
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `consolidate persists each candidate via repository`() = runTest {
        val repo = mockk<MemoryRepository>(relaxed = true)
        coEvery { repo.addMemory(any()) } returns "mem-id"
        val consolidator = MemoryConsolidator(repo)

        val messages = listOf(
            Message(
                id = "m1",
                conversationId = "c1",
                role = MessageRole.USER,
                content = "Remember that I'm vegetarian.",
                agentRole = null,
                timestamp = 0,
            ),
            Message(
                id = "m2",
                conversationId = "c1",
                role = MessageRole.USER,
                content = "Also note that I'm allergic to peanuts.",
                agentRole = null,
                timestamp = 0,
            ),
        )
        val persisted = consolidator.consolidate("c1", messages)
        assertEquals(2, persisted)
    }
}
