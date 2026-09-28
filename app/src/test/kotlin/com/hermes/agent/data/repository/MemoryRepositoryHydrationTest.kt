package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.MemoryDao
import com.hermes.agent.data.local.entity.MemoryEntity
import com.hermes.agent.data.memory.HashingEmbeddingService
import com.hermes.agent.data.memory.InMemoryVectorStore
import com.hermes.agent.util.DispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryRepositoryHydrationTest {

    private fun row(id: String, content: String) = MemoryEntity(
        id = id, content = content, embedding = null, relevanceScore = 1f,
        createdAt = 0, lastAccessedAt = 0, accessCount = 0,
    )

    @Test
    fun `memories saved before a restart are searchable after it`() = runTest {
        // On the tablet the vector index is in RAM only. After a restart one new
        // conversation summary was indexed and the eight older facts were not, so
        // the user-model build saw one fact and quietly gave up.
        val stored = listOf(
            row("a", "User prefers metric units."),
            row("b", "User's car is a 2019 Honda City."),
            row("c", "User wants short replies."),
        )
        val dao = mockk<MemoryDao>(relaxed = true) {
            every { observeAll() } returns flowOf(stored)
            coEvery { getById(any()) } answers { stored.firstOrNull { it.id == firstArg() } }
        }
        val dispatchers = mockk<DispatcherProvider> { every { io } returns Dispatchers.Unconfined }
        val repo = MemoryRepositoryImpl(dao, HashingEmbeddingService(), InMemoryVectorStore(), dispatchers)

        repo.addMemory("Conversation Summary: talked about scooters.")
        coEvery { dao.getById(any()) } answers {
            (stored + row(firstArg(), "Conversation Summary: talked about scooters.")).firstOrNull { it.id == firstArg() }
        }

        val found = repo.searchMemories("", limit = 200).map { it.content }.toSet()

        assertEquals(4, found.size)
        assertEquals(true, found.containsAll(stored.map { it.content }))
    }
}
