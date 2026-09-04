package com.hermes.agent.ui.postoffice

import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.repository.ConversationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostOfficeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `conversations filters only po- prefixed and PO titled threads`() = runTest {
        val sampleConversations = listOf(
            Conversation(id = "po-home-card", title = "PO: postoffice-home-card", createdAt = 1000, updatedAt = 1000),
            Conversation(id = "po-inbox", title = "Post Office", createdAt = 1000, updatedAt = 1000),
            Conversation(id = "abc-123", title = "PO: Persistence", createdAt = 1000, updatedAt = 1000),
            Conversation(id = "chat-456", title = "Regular chat", createdAt = 1000, updatedAt = 1000),
            Conversation(id = "regular-uuid", title = "Another normal thread", createdAt = 1000, updatedAt = 1000),
        )

        every { conversationRepository.observeConversations() } returns flowOf(sampleConversations)

        val viewModel = PostOfficeViewModel(conversationRepository)
        backgroundScope.launch(testDispatcher) {
            viewModel.conversations.collect {}
        }
        advanceUntilIdle()

        val filtered = viewModel.conversations.value
        assertEquals(3, filtered.size)
        assertEquals("po-home-card", filtered[0].id)
        assertEquals("po-inbox", filtered[1].id)
        assertEquals("abc-123", filtered[2].id)
    }

    @Test
    fun `conversations returns empty when no post office chats exist`() = runTest {
        val sampleConversations = listOf(
            Conversation(id = "chat-1", title = "Regular chat 1", createdAt = 1000, updatedAt = 1000),
            Conversation(id = "chat-2", title = "Regular chat 2", createdAt = 1000, updatedAt = 1000),
        )

        every { conversationRepository.observeConversations() } returns flowOf(sampleConversations)

        val viewModel = PostOfficeViewModel(conversationRepository)
        backgroundScope.launch(testDispatcher) {
            viewModel.conversations.collect {}
        }
        advanceUntilIdle()

        val filtered = viewModel.conversations.value
        assertEquals(0, filtered.size)
    }
}
