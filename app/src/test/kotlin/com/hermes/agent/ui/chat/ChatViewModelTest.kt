package com.hermes.agent.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.hermes.agent.domain.model.ChatStreamEvent
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.data.agent.ClarificationBus
import com.hermes.agent.data.agent.TodoStore
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.data.voice.VoiceInputManager
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 1 unit tests for [ChatViewModel].
 *
 * The VM exposes a [kotlinx.coroutines.flow.StateFlow] backed by
 * `stateIn(WhileSubscribed)`, so the first emission is always the
 * `initialValue` (`ChatUiState()`). Tests use [advanceUntilIdle] after
 * triggering actions so the upstream combine flushes its real state
 * before assertions read it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    /** A [SettingsRepository] stub that emits default settings once — a bare
     *  relaxed mock's `observe()` never emits, which would silently starve
     *  every `combine()` downstream of it in [ChatViewModel.uiState]. */
    private fun fakeSettingsRepository(): SettingsRepository =
        mockk<SettingsRepository>(relaxed = true).also {
            every { it.observe() } returns flowOf(UserSettings())
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendMessage accumulates streamed tokens then clears on complete`() = runTest {
        val conversationId = "conv-1"
        val savedState = SavedStateHandle(mapOf("conversationId" to conversationId))
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        every { conversationRepo.observeMessages(conversationId) } returns flowOf(emptyList())
        every { conversationRepo.observeConversation(conversationId) } returns flowOf(
            Conversation(id = conversationId, title = "Test", createdAt = 0, updatedAt = 0)
        )

        val streamFlow = MutableSharedFlow<ChatStreamEvent>(extraBufferCapacity = 10)
        val chatRepo = mockk<ChatRepository>()
        every { chatRepo.sendMessage(conversationId, any()) } returns streamFlow

        val vm = ChatViewModel(savedState, conversationRepo, chatRepo, mockk(relaxed = true), mockk(relaxed = true), ClarificationBus(), TodoStore(), fakeSettingsRepository())
        advanceUntilIdle()

        // Trigger send
        vm.sendMessage("Hello")
        advanceUntilIdle()

        // Before any tokens arrive, the VM should be in isSending=true with empty streaming text.
        assertTrue("expected isSending=true after send", vm.uiState.value.isSending)
        assertEquals("", vm.uiState.value.streamingText)

        // Emit a token
        streamFlow.emit(ChatStreamEvent.Token("Hello "))
        advanceUntilIdle()
        assertEquals("Hello ", vm.uiState.value.streamingText)

        // Emit complete
        streamFlow.emit(
            ChatStreamEvent.Complete(
                Message(
                    id = "m1",
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = "Hello ",
                    timestamp = 0L,
                )
            )
        )
        advanceUntilIdle()

        assertFalse("expected isSending=false after complete", vm.uiState.value.isSending)
        assertNull("expected streamingText=null after complete", vm.uiState.value.streamingText)
    }

    @Test
    fun `sendMessage surfaces error event as uiState errorMessage`() = runTest {
        val conversationId = "conv-1"
        val savedState = SavedStateHandle(mapOf("conversationId" to conversationId))
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        every { conversationRepo.observeMessages(conversationId) } returns flowOf(emptyList())
        every { conversationRepo.observeConversation(conversationId) } returns flowOf(null)

        val streamFlow = MutableSharedFlow<ChatStreamEvent>(extraBufferCapacity = 10)
        val chatRepo = mockk<ChatRepository>()
        every { chatRepo.sendMessage(conversationId, any()) } returns streamFlow

        val vm = ChatViewModel(savedState, conversationRepo, chatRepo, mockk(relaxed = true), mockk(relaxed = true), ClarificationBus(), TodoStore(), fakeSettingsRepository())
        advanceUntilIdle()

        vm.sendMessage("oops")
        advanceUntilIdle()

        streamFlow.emit(ChatStreamEvent.Error(RuntimeException("boom")))
        advanceUntilIdle()

        assertNotNull("expected an error message", vm.uiState.value.errorMessage)
        assertTrue(
            "expected error to contain 'boom'",
            vm.uiState.value.errorMessage!!.contains("boom")
        )
    }

    @Test
    fun `empty message is ignored`() = runTest {
        val savedState = SavedStateHandle(mapOf("conversationId" to "conv-x"))
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        every { conversationRepo.observeMessages(any()) } returns flowOf(emptyList())
        every { conversationRepo.observeConversation(any()) } returns flowOf(null)
        val chatRepo = mockk<ChatRepository>(relaxed = true)

        val vm = ChatViewModel(savedState, conversationRepo, chatRepo, mockk(relaxed = true), mockk(relaxed = true), ClarificationBus(), TodoStore(), fakeSettingsRepository())
        advanceUntilIdle()

        vm.sendMessage("   ")
        advanceUntilIdle()

        assertFalse("expected isSending=false for empty input", vm.uiState.value.isSending)
    }

    @Test
    fun `cancel resets ephemeral state`() = runTest {
        val conversationId = "conv-1"
        val savedState = SavedStateHandle(mapOf("conversationId" to conversationId))
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        every { conversationRepo.observeMessages(conversationId) } returns flowOf(emptyList())
        every { conversationRepo.observeConversation(conversationId) } returns flowOf(null)

        val streamFlow = MutableSharedFlow<ChatStreamEvent>(extraBufferCapacity = 10)
        val chatRepo = mockk<ChatRepository>()
        every { chatRepo.sendMessage(conversationId, any()) } returns streamFlow

        val vm = ChatViewModel(savedState, conversationRepo, chatRepo, mockk(relaxed = true), mockk(relaxed = true), ClarificationBus(), TodoStore(), fakeSettingsRepository())
        advanceUntilIdle()

        vm.sendMessage("hello")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isSending)

        vm.cancel()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSending)
        assertNull(vm.uiState.value.streamingText)
    }

    @Test
    fun `conversationId is read from saved state handle`() = runTest {
        val savedState = SavedStateHandle(mapOf("conversationId" to "abc-123"))
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        every { conversationRepo.observeMessages(any()) } returns flowOf(emptyList())
        every { conversationRepo.observeConversation(any()) } returns flowOf(null)
        val chatRepo = mockk<ChatRepository>(relaxed = true)

        val vm = ChatViewModel(savedState, conversationRepo, chatRepo, mockk(relaxed = true), mockk(relaxed = true), ClarificationBus(), TodoStore(), fakeSettingsRepository())
        assertEquals("abc-123", vm.conversationId)
    }
}
