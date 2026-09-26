package com.hermes.agent.data.repository

import com.hermes.agent.data.chat.ReasoningStore
import com.hermes.agent.data.llm.ConversationCompressor
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.util.DispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K21 (issue #13): a turn whose collector is cancelled (the ViewModel was cleared
 * mid-reply) must still leave an assistant message behind, not an unanswered thread.
 */
class ChatRepositoryInterruptTest {

    @Test
    fun `cancelled turn keeps the partial reply`() = runTest {
        val saved = runInterruptedTurn(tokensBeforeCancel = listOf("Half ", "an answer"))

        assertEquals(MessageRole.ASSISTANT, saved.single().role)
        assertEquals("Half an answer", saved.single().content)
    }

    @Test
    fun `cancelled turn with no tokens leaves an interrupted notice`() = runTest {
        val saved = runInterruptedTurn(tokensBeforeCancel = emptyList())

        assertEquals(MessageRole.ASSISTANT, saved.single().role)
        assertTrue(saved.single().content.contains("interrupted"))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.runInterruptedTurn(
        tokensBeforeCancel: List<String>,
    ): List<Message> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = mockk<DispatcherProvider> {
            every { io } returns dispatcher
            every { default } returns dispatcher
            every { main } returns dispatcher
            every { unconfined } returns dispatcher
        }
        val messages = mutableListOf<Message>()
        val conversations = mockk<ConversationRepository>(relaxed = true) {
            coEvery { addMessage(any(), any()) } answers {
                messages += secondArg<Message>()
                secondArg<Message>().id
            }
            coEvery { getRecentMessages(any(), any()) } returns emptyList()
        }
        val orchestrator = mockk<Orchestrator> {
            every { run(any(), any(), any(), any()) } returns flow {
                tokensBeforeCancel.forEach { emit(OrchestratorEvent.ReplyToken(it)) }
                awaitCancellation()
            }
        }
        val compressor = mockk<ConversationCompressor> {
            coEvery { brief(any(), any()) } returns null
        }
        val repo = ChatRepositoryImpl(
            conversationRepository = conversations,
            memoryRepository = mockk<MemoryRepository>(relaxed = true),
            router = mockk<LlmRouter>(relaxed = true),
            orchestrator = orchestrator,
            compressor = compressor,
            dispatchers = dispatchers,
            reasoningStore = mockk<ReasoningStore>(relaxed = true),
        )

        val job = launch {
            repo.sendMessageOrchestrated("c1", "hello", ExecutionOrigin.INTERACTIVE).collect {}
        }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()

        // The first message is the user's own; everything after it is the reply side.
        assertEquals(MessageRole.USER, messages.first().role)
        return messages.drop(1)
    }
}
