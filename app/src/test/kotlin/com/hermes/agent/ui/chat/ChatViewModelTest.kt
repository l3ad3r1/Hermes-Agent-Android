package com.hermes.agent.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.hermes.agent.data.agent.ClarificationBus
import com.hermes.agent.data.agent.TodoStore
import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.data.voice.VoiceInputManager
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.model.ExecutionPlan
import com.hermes.agent.domain.model.ExecutionStep
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import com.hermes.agent.domain.tool.ToolConfirmationService
import kotlinx.coroutines.launch
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
 * Unit tests for [ChatViewModel].
 *
 * The VM exposes a [kotlinx.coroutines.flow.StateFlow] backed by
 * `stateIn(WhileSubscribed)`, so the first emission is always the
 * `initialValue` (`ChatUiState()`). Tests use [advanceUntilIdle] after
 * triggering actions so the upstream combine flushes its real state
 * before assertions read it.
 *
 * [ChatViewModel.sendMessage] goes through
 * [ChatRepository.sendMessageOrchestrated] (Phase 2 rich flow) — the
 * Phase 1 [ChatRepository.sendMessage]/ChatStreamEvent path is not used
 * by the VM anymore, so tests stub the orchestrated flow.
 *
 * `stateIn(WhileSubscribed)` only runs the upstream combine while there is
 * an active collector, so each state-transition test collects [ChatViewModel.uiState]
 * in [kotlinx.coroutines.test.TestScope.backgroundScope] before asserting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    /** A [SettingsRepository] stub that emits [settings] once — a bare
     *  relaxed mock's `observe()` never emits, which would silently starve
     *  every `combine()` downstream of it in [ChatViewModel.uiState]. */
    private fun fakeSettingsRepository(settings: UserSettings = UserSettings()): SettingsRepository =
        mockk<SettingsRepository>(relaxed = true).also {
            every { it.observe() } returns flowOf(settings)
        }

    private fun fakeConversationRepository(conversationId: String): ConversationRepository =
        mockk<ConversationRepository>(relaxed = true).also {
            every { it.observeMessages(conversationId) } returns flowOf(emptyList())
            every { it.observeConversation(conversationId) } returns flowOf(
                Conversation(id = conversationId, title = "Test", createdAt = 0, updatedAt = 0)
            )
        }

    private fun buildViewModel(
        conversationId: String = "conv-1",
        chatRepo: ChatRepository = mockk(relaxed = true),
        settingsRepository: SettingsRepository = fakeSettingsRepository(),
        planRepository: ExecutionPlanRepository? = null,
    ): ChatViewModel {
        val plans = planRepository ?: mockk<ExecutionPlanRepository>(relaxed = true).also {
            every { it.observeLatest(conversationId) } returns flowOf(null)
        }
        return ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversationId)),
        conversationRepository = fakeConversationRepository(conversationId),
        chatRepository = chatRepo,
        voiceInputManager = mockk<VoiceInputManager>(relaxed = true),
        voiceOutputManager = mockk<VoiceOutputManager>(relaxed = true),
        clarificationBus = ClarificationBus(),
        todoStore = TodoStore(),
        settingsRepository = settingsRepository,
        toolConfirmationService = mockk<ToolConfirmationService>(relaxed = true),
        executionPlanRepository = plans,
        )
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
    fun `restores latest persisted plan with stable step ids`() = runTest {
        val repository = mockk<ExecutionPlanRepository>()
        every { repository.observeLatest("conv-1") } returns flowOf(
            ExecutionPlan(
                id = "plan",
                conversationId = "conv-1",
                userMessage = "test",
                steps = listOf(
                    ExecutionStep("step-a", AgentRole.RESEARCH, "Research"),
                    ExecutionStep("step-b", AgentRole.CONVERSATIONAL, "Answer"),
                ),
                createdAt = 1,
            ),
        )
        val vm = buildViewModel(planRepository = repository)
        backgroundScope.launch { vm.uiState.collect { } }

        advanceUntilIdle()

        assertEquals(listOf("step-a", "step-b"), vm.uiState.value.currentPlan?.steps?.map { it.id })
    }

    @Test
    fun `sendMessage accumulates streamed tokens then clears on complete`() = runTest {
        val conversationId = "conv-1"
        val eventFlow = MutableSharedFlow<OrchestratorEvent>(extraBufferCapacity = 10)
        val chatRepo = mockk<ChatRepository>()
        every { chatRepo.sendMessageOrchestrated(conversationId, any(), any()) } returns eventFlow

        val vm = buildViewModel(conversationId, chatRepo)
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.sendMessage("Hello")
        advanceUntilIdle()

        // Before any tokens arrive, the VM should be in isSending=true with empty streaming text.
        assertTrue("expected isSending=true after send", vm.uiState.value.isSending)
        assertEquals("", vm.uiState.value.streamingText)

        eventFlow.emit(OrchestratorEvent.ReplyToken("Hello "))
        advanceUntilIdle()
        assertEquals("Hello ", vm.uiState.value.streamingText)

        eventFlow.emit(
            OrchestratorEvent.ReplyComplete(
                finalText = "Hello ",
                agentRole = AgentRole.CONVERSATIONAL,
                isOnDevice = true,
            )
        )
        advanceUntilIdle()

        assertFalse("expected isSending=false after complete", vm.uiState.value.isSending)
        assertNull("expected streamingText=null after complete", vm.uiState.value.streamingText)
    }

    @Test
    fun `sendMessage surfaces Failed event as uiState errorMessage`() = runTest {
        val conversationId = "conv-1"
        val eventFlow = MutableSharedFlow<OrchestratorEvent>(extraBufferCapacity = 10)
        val chatRepo = mockk<ChatRepository>()
        every { chatRepo.sendMessageOrchestrated(conversationId, any(), any()) } returns eventFlow

        val vm = buildViewModel(conversationId, chatRepo)
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.sendMessage("oops")
        advanceUntilIdle()

        eventFlow.emit(OrchestratorEvent.Failed("boom"))
        advanceUntilIdle()

        assertNotNull("expected an error message", vm.uiState.value.errorMessage)
        assertTrue(
            "expected error to contain 'boom'",
            vm.uiState.value.errorMessage!!.contains("boom")
        )
    }

    @Test
    fun `empty message is ignored`() = runTest {
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val vm = buildViewModel(chatRepo = chatRepo)
        advanceUntilIdle()

        vm.sendMessage("   ")
        advanceUntilIdle()

        assertFalse("expected isSending=false for empty input", vm.uiState.value.isSending)
    }

    @Test
    fun `cancel resets ephemeral state`() = runTest {
        val conversationId = "conv-1"
        val eventFlow = MutableSharedFlow<OrchestratorEvent>(extraBufferCapacity = 10)
        val chatRepo = mockk<ChatRepository>()
        every { chatRepo.sendMessageOrchestrated(conversationId, any(), any()) } returns eventFlow

        val vm = buildViewModel(conversationId, chatRepo)
        backgroundScope.launch { vm.uiState.collect { } }
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
        val vm = buildViewModel(conversationId = "abc-123")
        assertEquals("abc-123", vm.conversationId)
    }

    @Test
    fun `tool calls are visible while streaming when showToolCalls is on`() = runTest {
        val conversationId = "conv-1"
        val eventFlow = MutableSharedFlow<OrchestratorEvent>(extraBufferCapacity = 10)
        val chatRepo = mockk<ChatRepository>()
        every { chatRepo.sendMessageOrchestrated(conversationId, any(), any()) } returns eventFlow

        val vm = buildViewModel(
            conversationId, chatRepo,
            settingsRepository = fakeSettingsRepository(UserSettings(showToolCalls = true)),
        )
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.sendMessage("look this up")
        advanceUntilIdle()

        eventFlow.emit(
            OrchestratorEvent.ToolCallRequested(
                call = ToolCall(id = "t1", name = "web_search", arguments = emptyMap()),
                requiresConfirmation = false,
            )
        )
        eventFlow.emit(OrchestratorEvent.ReplyToken("Searching…"))
        advanceUntilIdle()

        val streaming = vm.uiState.value.visibleItems.filterIsInstance<ChatListItem.StreamingItem>().single()
        assertEquals("expected the tool call card to be visible", 1, streaming.toolCalls.size)
        assertEquals("web_search", streaming.toolCalls.single().name)
    }

    @Test
    fun `tool calls are hidden while streaming when showToolCalls is off`() = runTest {
        val conversationId = "conv-1"
        val eventFlow = MutableSharedFlow<OrchestratorEvent>(extraBufferCapacity = 10)
        val chatRepo = mockk<ChatRepository>()
        every { chatRepo.sendMessageOrchestrated(conversationId, any(), any()) } returns eventFlow

        val vm = buildViewModel(
            conversationId, chatRepo,
            settingsRepository = fakeSettingsRepository(UserSettings(showToolCalls = false)),
        )
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.sendMessage("look this up")
        advanceUntilIdle()

        eventFlow.emit(
            OrchestratorEvent.ToolCallRequested(
                call = ToolCall(id = "t1", name = "web_search", arguments = emptyMap()),
                requiresConfirmation = false,
            )
        )
        eventFlow.emit(OrchestratorEvent.ReplyToken("Searching…"))
        advanceUntilIdle()

        val streaming = vm.uiState.value.visibleItems.filterIsInstance<ChatListItem.StreamingItem>().single()
        assertTrue("expected tool call cards to be withheld", streaming.toolCalls.isEmpty())
        // The reply itself still streams — opacity hides tools, not the answer.
        assertEquals("Searching…", streaming.text)
    }
}
