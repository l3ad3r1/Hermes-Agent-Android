package com.hermes.agent.data.memory

import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.LlmResponse
import com.hermes.agent.domain.model.Memory
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.util.DispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the persisted, retry-safe user-model rebuild trigger — the fix for the
 * old in-memory counter that reset every process death.
 */
class UserModelServiceTest {

    private lateinit var provider: CloudLlmProvider
    private lateinit var memory: MemoryRepository
    private lateinit var learningState: LearningState
    private lateinit var service: UserModelService

    private val dispatcher = UnconfinedTestDispatcher()

    private fun memory(content: String) = Memory(
        id = content.hashCode().toString(),
        content = content,
        createdAt = 0,
        lastAccessedAt = 0,
    )

    @Before
    fun setUp() {
        provider = mockk(relaxed = true)
        memory = mockk(relaxed = true)
        learningState = mockk(relaxed = true)
        val dispatchers = mockk<DispatcherProvider>()
        io.mockk.every { dispatchers.io } returns dispatcher
        service = UserModelService(provider, memory, learningState, dispatchers)

        // Enough non-model facts to allow a rebuild.
        coEvery { memory.searchMemories(any(), any()) } returns listOf(
            memory("User's name is Rinu"),
            memory("User is a software engineer"),
            memory("User lives in India"),
        )
        coEvery { provider.isAvailable() } returns true
        coEvery { provider.complete(any()) } returns
            LlmResponse("Rinu is a software engineer in India who values concise answers.", 20, "m")
    }

    @Test
    fun `rebuilds and advances marker once the threshold is crossed`() = runTest(dispatcher) {
        coEvery { learningState.incrementConversationCount() } returns 5
        coEvery { learningState.userModelRebuiltAt() } returns 0

        service.onConversationComplete()

        coVerify(exactly = 1) { provider.complete(any()) }
        coVerify(exactly = 1) { learningState.setUserModelRebuiltAt(5) }
    }

    @Test
    fun `does not rebuild before the threshold`() = runTest(dispatcher) {
        coEvery { learningState.incrementConversationCount() } returns 3
        coEvery { learningState.userModelRebuiltAt() } returns 0

        service.onConversationComplete()

        coVerify(exactly = 0) { provider.complete(any()) }
        coVerify(exactly = 0) { learningState.setUserModelRebuiltAt(any()) }
    }

    @Test
    fun `does not advance marker when rebuild is skipped (cloud unavailable)`() = runTest(dispatcher) {
        coEvery { learningState.incrementConversationCount() } returns 5
        coEvery { learningState.userModelRebuiltAt() } returns 0
        coEvery { provider.isAvailable() } returns false

        service.onConversationComplete()

        // Marker stays put so the rebuild is retried on the next conversation.
        coVerify(exactly = 0) { learningState.setUserModelRebuiltAt(any()) }
    }

    @Test
    fun `currentModel reads the prefixed memory directly and strips the prefix`() =
        runTest(dispatcher) {
            coEvery { memory.newestMemoryWithPrefix(UserModelService.MODEL_PREFIX) } returns
                memory("${UserModelService.MODEL_PREFIX}Rinu prefers concise answers.")

            val model = service.currentModel()

            assertEquals("Rinu prefers concise answers.", model)
            // The old path vector-searched 200 memories; the fast path must not.
            coVerify(exactly = 0) { memory.searchMemories(any(), any()) }
        }

    @Test
    fun `currentModel returns null when no model memory exists`() = runTest(dispatcher) {
        coEvery { memory.newestMemoryWithPrefix(UserModelService.MODEL_PREFIX) } returns null

        assertNull(service.currentModel())
    }
}
