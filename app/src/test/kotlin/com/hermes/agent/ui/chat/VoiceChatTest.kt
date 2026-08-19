package com.hermes.agent.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.hermes.agent.data.agent.ClarificationBus
import com.hermes.agent.data.agent.TodoStore
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.data.voice.VoiceInputEvent
import com.hermes.agent.data.voice.VoiceInputManager
import com.hermes.agent.data.voice.VoiceOutputEvent
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import com.hermes.agent.domain.tool.ToolConfirmationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.hermes.agent.domain.model.AgentRole
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Voice behaviour: replies are read aloud only in hands-free voice chat, and
 * that mode is a strict listen → send → speak → listen cycle.
 *
 * Hermes used to read every reply aloud whenever a TTS engine existed, whether
 * or not the user had spoken to it — a typed conversation started talking. The
 * first test here pins that shut.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChatTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun buildViewModel(
        chatRepo: ChatRepository,
        voiceInput: VoiceInputManager,
        voiceOutput: VoiceOutputManager,
    ): ChatViewModel {
        val conversationId = "conv-1"
        val conversations = mockk<ConversationRepository>(relaxed = true).also {
            every { it.observeMessages(conversationId) } returns flowOf(emptyList())
            every { it.observeConversation(conversationId) } returns flowOf(
                Conversation(id = conversationId, title = "Test", createdAt = 0, updatedAt = 0),
            )
        }
        val settings = mockk<SettingsRepository>(relaxed = true).also {
            every { it.observe() } returns flowOf(UserSettings())
        }
        val plans = mockk<ExecutionPlanRepository>(relaxed = true).also {
            every { it.observeLatest(conversationId) } returns flowOf(null)
        }
        return ChatViewModel(
            savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversationId)),
            conversationRepository = conversations,
            chatRepository = chatRepo,
            voiceInputManager = voiceInput,
            voiceOutputManager = voiceOutput,
            clarificationBus = ClarificationBus(),
            todoStore = TodoStore(),
            settingsRepository = settings,
            toolConfirmationService = mockk<ToolConfirmationService>(relaxed = true),
            executionPlanRepository = plans,
            ultraSkillInterceptor = mockk<com.hermes.agent.domain.agent.UltraSkillInterceptor>(relaxed = true).also {
                io.mockk.coEvery { it.intercept(any(), any()) } returns false
            },
        )
    }

    /** A chat repo whose turn completes with [reply]. */
    private fun replyingRepo(reply: String = "the reply"): ChatRepository =
        mockk<ChatRepository>(relaxed = true).also {
            every { it.sendMessageOrchestrated(any(), any(), any()) } returns flowOf(
                OrchestratorEvent.ReplyToken(reply),
                OrchestratorEvent.ReplyComplete(reply, AgentRole.DEFAULT, true),
            )
        }

    /**
     * A recogniser that yields [events] on its first turn and hears nothing
     * afterwards.
     *
     * The "nothing afterwards" matters. A mock that returns a transcript on
     * every call turns the voice-chat loop into an infinite one — listen, send,
     * speak, listen — and `advanceUntilIdle` never returns. A real recogniser
     * blocks on silence, so this is the faithful shape.
     */
    private fun availableMic(vararg events: VoiceInputEvent): VoiceInputManager =
        mockk<VoiceInputManager>(relaxed = true).also { mic ->
            var turn = 0
            every { mic.isAvailable() } returns true
            every { mic.listen(any()) } answers {
                if (turn++ == 0) flowOf(*events) else emptyFlow()
            }
        }

    private fun readyTts(): VoiceOutputManager =
        mockk<VoiceOutputManager>(relaxed = true).also {
            every { it.isAvailable() } returns true
            every { it.speak(any(), any()) } returns flowOf(VoiceOutputEvent.Done)
        }

    @Test
    fun `a typed reply is never spoken aloud`() = runTest {
        val tts = readyTts()
        val vm = buildViewModel(replyingRepo(), availableMic(), tts)

        vm.sendMessage("hello")
        advanceUntilIdle()

        // The whole point of the change: TTS is available and a reply arrived,
        // and Hermes still said nothing, because the user typed.
        verify(exactly = 0) { tts.speak(any(), any()) }
    }

    @Test
    fun `voice chat speaks the reply`() = runTest {
        val tts = readyTts()
        val vm = buildViewModel(replyingRepo("spoken answer"), availableMic(), tts)

        vm.toggleVoiceChat()
        vm.sendMessage("hello")
        advanceUntilIdle()

        verify { tts.speak("spoken answer", any()) }
    }

    @Test
    fun `voice chat sends what it heard instead of parking it in the input bar`() = runTest {
        val chatRepo = replyingRepo()
        val vm = buildViewModel(
            chatRepo,
            availableMic(VoiceInputEvent.Final("what is the weather")),
            readyTts(),
        )
        backgroundScope.launch { vm.uiState.collect { } }

        vm.toggleVoiceChat()
        advanceUntilIdle()

        verify { chatRepo.sendMessageOrchestrated(any(), "what is the weather", any()) }
        // Nothing is left staged in the composer — hands-free means no tap.
        assertEquals("", vm.uiState.value.inputPrefill)
    }

    @Test
    fun `dictation still only fills the input bar`() = runTest {
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val vm = buildViewModel(
            chatRepo,
            availableMic(VoiceInputEvent.Final("a dictated note")),
            readyTts(),
        )
        backgroundScope.launch { vm.uiState.collect { } }

        vm.toggleVoiceInput()
        advanceUntilIdle()

        assertEquals("a dictated note", vm.uiState.value.inputPrefill)
        verify(exactly = 0) { chatRepo.sendMessageOrchestrated(any(), any(), any()) }
    }

    @Test
    fun `ending voice chat stops the speaker and the microphone`() = runTest {
        val tts = readyTts()
        val vm = buildViewModel(replyingRepo(), availableMic(), tts)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.toggleVoiceChat()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.voiceChatActive)

        vm.toggleVoiceChat()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.voiceChatActive)
        assertFalse(vm.uiState.value.isListening)
        verify { tts.stop() }
    }

    @Test
    fun `a turn is not spoken twice when the agent used the speak tool`() = runTest {
        val tts = readyTts()
        val chatRepo = mockk<ChatRepository>(relaxed = true).also {
            every { it.sendMessageOrchestrated(any(), any(), any()) } returns flowOf(
                OrchestratorEvent.ToolCallRequested(
                    com.hermes.agent.data.llm.ToolCall("1", "speak", buildMap { }),
                    false,
                ),
                OrchestratorEvent.ReplyComplete("also written out", AgentRole.DEFAULT, true),
            )
        }
        val vm = buildViewModel(chatRepo, availableMic(), tts)

        vm.toggleVoiceChat()
        vm.sendMessage("say something")
        advanceUntilIdle()

        // The agent already chose what to say aloud; reading the reply on top
        // of it would say the turn twice.
        verify(exactly = 0) { tts.speak("also written out", any()) }
    }
}
