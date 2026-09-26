package com.hermes.agent.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.hermes.agent.data.agent.ClarificationBus
import com.hermes.agent.data.agent.TodoStore
import com.hermes.agent.data.chat.BranchStore
import com.hermes.agent.data.chat.ReasoningStore
import com.hermes.agent.data.voice.VoiceInputManager
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.tool.ToolConfirmationService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Editing a turn used to delete it and everything after it. These check that the earlier version
 * is now kept as a branch that can be brought back, against a repository that really removes and
 * re-adds messages, so the rewind and the re-insert are exercised together with the bookkeeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatBranchingTest {

    private val dispatcher = StandardTestDispatcher()
    private val cid = "conv-1"
    private val chat = MutableStateFlow<List<Message>>(emptyList())

    private fun msg(id: String, role: MessageRole, text: String = id) =
        Message(id = id, conversationId = cid, role = role, content = text, timestamp = chat.value.size.toLong())

    private fun repository(): ConversationRepository = mockk<ConversationRepository>(relaxed = true).also { repo ->
        every { repo.observeMessages(cid) } returns chat
        every { repo.observeConversation(cid) } returns flowOf(Conversation(id = cid, title = "T", createdAt = 0, updatedAt = 0))
        coEvery { repo.rewindTo(cid, any()) } answers {
            val at = chat.value.indexOfFirst { it.id == secondArg<Message>().id }
            val removed = chat.value.size - at
            chat.value = chat.value.take(at)
            removed
        }
        coEvery { repo.addMessage(cid, any()) } answers {
            chat.value = chat.value + secondArg<Message>()
            secondArg<Message>().id
        }
    }

    private fun viewModel(): ChatViewModel {
        val plans = mockk<ExecutionPlanRepository>(relaxed = true).also { every { it.observeLatest(cid) } returns flowOf(null) }
        val settings = mockk<SettingsRepository>(relaxed = true).also { every { it.observe() } returns flowOf(UserSettings()) }
        // The branch store is only the file behind the state; the state itself is what is checked.
        val branchStore = mockk<BranchStore>(relaxed = true).also { coEvery { it.load(any()) } returns emptyList() }
        return ChatViewModel(
            savedStateHandle = SavedStateHandle(mapOf("conversationId" to cid)),
            conversationRepository = repository(),
            chatRepository = mockk<ChatRepository>(relaxed = true),
            voiceInputManager = mockk<VoiceInputManager>(relaxed = true),
            voiceOutputManager = mockk<VoiceOutputManager>(relaxed = true),
            clarificationBus = ClarificationBus(),
            todoStore = TodoStore(),
            settingsRepository = settings,
            reasoningStore = mockk<ReasoningStore>(relaxed = true),
            branchStore = branchStore,
            toolConfirmationService = mockk<ToolConfirmationService>(relaxed = true),
            executionPlanRepository = plans,
            ultraSkillInterceptor = mockk<com.hermes.agent.domain.agent.UltraSkillInterceptor>(relaxed = true),
        )
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun ids() = chat.value.map { it.id }

    @Test
    fun `editing keeps the earlier version and switching moves between the two`() = runTest(dispatcher) {
        val u1 = msg("u1", MessageRole.USER)
        val a1 = msg("a1", MessageRole.ASSISTANT)
        val u2 = msg("u2", MessageRole.USER, "first wording")
        val a2 = msg("a2", MessageRole.ASSISTANT)
        chat.value = listOf(u1, a1, u2, a2)
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        backgroundScope.launch { vm.branches.collect { } }
        advanceUntilIdle()

        // Edit the second question: the chat is cut back, and the old answer is not lost.
        vm.editMessage(u2)
        advanceUntilIdle()
        assertEquals(listOf("u1", "a1"), ids())

        // The reworded question and its answer are sent as usual.
        chat.value = chat.value + msg("u2b", MessageRole.USER, "second wording") + msg("a2b", MessageRole.ASSISTANT)
        advanceUntilIdle()
        val info = vm.branches.value["u2b"]
        assertNotNull("the switcher sits on the new branch's first message", info)
        assertEquals(2, info!!.position)
        assertEquals(2, info.count)

        vm.switchBranch(info, 1)
        advanceUntilIdle()
        assertEquals(listOf("u1", "a1", "u2", "a2"), ids())
        assertEquals("first wording", chat.value[2].content)
        assertEquals(1, vm.branches.value["u2"]!!.position)

        vm.switchBranch(vm.branches.value["u2"]!!, 2)
        advanceUntilIdle()
        assertEquals(listOf("u1", "a1", "u2b", "a2b"), ids())
    }

    @Test
    fun `an edit that is abandoned can still be undone from the parent message`() = runTest(dispatcher) {
        val u1 = msg("u1", MessageRole.USER)
        val a1 = msg("a1", MessageRole.ASSISTANT)
        val u2 = msg("u2", MessageRole.USER)
        chat.value = listOf(u1, a1, u2)
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        backgroundScope.launch { vm.branches.collect { } }
        advanceUntilIdle()

        vm.editMessage(u2)
        advanceUntilIdle()
        assertEquals(listOf("u1", "a1"), ids())

        val info = vm.branches.value["a1"]
        assertNotNull("with nothing sent yet the switcher is on the parent", info)
        vm.switchBranch(info!!, 1)
        advanceUntilIdle()
        assertEquals(listOf("u1", "a1", "u2"), ids())
        assertNull("only one version is left, so no switcher", vm.branches.value["u2"])
    }
}
