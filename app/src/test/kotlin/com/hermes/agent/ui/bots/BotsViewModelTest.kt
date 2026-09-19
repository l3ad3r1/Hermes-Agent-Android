package com.hermes.agent.ui.bots

import com.hermes.agent.data.llm.LocalLlmManager
import com.hermes.agent.data.local.LocalBot
import com.hermes.agent.data.local.LocalBotStore
import com.hermes.agent.data.remote.BotProfileStore
import com.hermes.agent.data.remote.ChiefOfBots
import com.hermes.agent.data.remote.GatewayApiClient
import com.hermes.agent.data.remote.GatewayEvent
import com.hermes.agent.data.remote.RemoteJob
import com.hermes.agent.data.remote.RemoteProfile
import com.hermes.agent.data.voice.VoiceInputEvent
import com.hermes.agent.data.voice.VoiceInputManager
import com.hermes.agent.data.voice.VoiceOutputEvent
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.data.repository.ConversationRepositoryImpl
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val gateway = mockk<GatewayApiClient>(relaxed = true)
    private val settings = mockk<SettingsRepository>()
    private val profileStore = mockk<BotProfileStore>(relaxed = true)
    private val localBotStore = mockk<LocalBotStore>(relaxed = true)
    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val conversationRepository = mockk<ConversationRepositoryImpl>(relaxed = true)

    private val job = RemoteJob(
        id = "1f05fe8a4946", name = "Reddit bot drafting rounds", schedule = "0 9,12,15,18 * * *",
        enabled = true, state = "scheduled", nextRunAt = null, lastRunAt = null,
        lastStatus = null, lastError = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { profileStore.profiles } returns MutableStateFlow(listOf("default", "redditbot"))
        every { localBotStore.bots } returns MutableStateFlow(emptyList())
        every { localBotStore.chiefName } returns MutableStateFlow(ChiefOfBots.DEFAULT_NAME)
        every { settings.observe() } returns MutableStateFlow(
            UserSettings(
                remoteGatewayUrl = "http://pc.tailnet.ts.net:8642",
                remoteGatewayApiKey = "k".repeat(32),
            ),
        )
        coEvery { gateway.listJobs(any()) } returns listOf(job)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val toolConfirmationService = mockk<com.hermes.agent.domain.tool.ToolConfirmationService>(relaxed = true) {
        every { pendingRequest } returns kotlinx.coroutines.flow.MutableStateFlow(null)
    }

    private val localLlmManager = mockk<LocalLlmManager>(relaxed = true)
    private val voiceInput = mockk<VoiceInputManager>(relaxed = true)
    private val voiceOutput = mockk<VoiceOutputManager>(relaxed = true)

    private fun viewModel() = BotsViewModel(gateway, profileStore, localBotStore, chatRepository, conversationRepository, settings, toolConfirmationService, localLlmManager, voiceInput, voiceOutput)

    @Test
    fun `lists the default profile's bots without a prefix`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(job), vm.state.value.jobs)
        assertFalse(vm.state.value.loading)
        coVerify { gateway.listJobs(null) }
    }

    @Test
    fun `selecting a named profile reloads against that profile`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.select("redditbot")
        advanceUntilIdle()

        assertEquals("redditbot", vm.state.value.selected)
        coVerify { gateway.listJobs("redditbot") }
    }

    @Test
    fun `pausing a bot replaces it with the state the gateway returned`() = runTest(dispatcher) {
        val paused = job.copy(enabled = false, state = "paused")
        coEvery { gateway.jobAction(job.id, "pause", null) } returns paused
        val vm = viewModel()
        advanceUntilIdle()

        vm.act(job, "pause")
        advanceUntilIdle()

        assertEquals(listOf(paused), vm.state.value.jobs)
        assertNull(vm.state.value.busyJobId)
    }

    @Test
    fun `a failed action is reported and leaves the bot as it was`() = runTest(dispatcher) {
        coEvery { gateway.jobAction(any(), any(), any()) } throws java.io.IOException("run job failed: 404")
        val vm = viewModel()
        advanceUntilIdle()

        vm.act(job, "run")
        advanceUntilIdle()

        assertTrue(vm.state.value.error!!.contains("404"))
        assertEquals(listOf(job), vm.state.value.jobs)
        assertNull(vm.state.value.busyJobId)
    }

    @Test
    fun `a message streams the reply into its own turn`() = runTest(dispatcher) {
        coEvery { gateway.startRun("disk status?", "phone-bot-redditbot", "redditbot", null) } returns "run_abc"
        every { gateway.streamRunEvents("run_abc", "redditbot") } returns flowOf(
            GatewayEvent.MessageDelta("Disk "),
            GatewayEvent.MessageDelta("is fine."),
        )
        val vm = viewModel()
        advanceUntilIdle()
        vm.select("redditbot")
        advanceUntilIdle()

        vm.send("  disk status?  ")
        advanceUntilIdle()

        val turn = vm.state.value.turns.single()
        assertEquals("disk status?", turn.sent)
        assertEquals("Disk is fine.", turn.reply)
        assertFalse(turn.failed)
        assertFalse(vm.state.value.sending)
    }

    @Test
    fun `a desktop bot's turn is stored so its thread survives leaving the screen`() = runTest(dispatcher) {
        coEvery { gateway.startRun(any(), any(), any(), any()) } returns "run_abc"
        every { gateway.streamRunEvents(any(), any()) } returns flowOf(
            GatewayEvent.MessageDelta("Disk is fine."),
        )
        val vm = viewModel()
        advanceUntilIdle()
        vm.select("redditbot")
        advanceUntilIdle()

        vm.send("disk status?")
        advanceUntilIdle()

        // A desktop bot's thread really lives on the PC; the phone keeps its own copy under a
        // namespaced id, or the chat is empty again the moment the screen is left.
        coVerify { conversationRepository.ensureConversation("desktopbot_redditbot", "redditbot") }
        coVerify {
            conversationRepository.addMessage(
                "desktopbot_redditbot",
                match { it.role == MessageRole.USER && it.content == "disk status?" },
            )
        }
        coVerify {
            conversationRepository.addMessage(
                "desktopbot_redditbot",
                match { it.role == MessageRole.ASSISTANT && it.content == "Disk is fine." },
            )
        }
    }

    private fun storedMessage(role: MessageRole, content: String) = Message(
        id = "$role-$content",
        conversationId = "desktopbot_redditbot",
        role = role,
        content = content,
        timestamp = 0L,
    )

    @Test
    fun `a stored snapshot that arrives late does not blank the reply`() = runTest(dispatcher) {
        val storedMessages = MutableStateFlow(emptyList<Message>())
        every { conversationRepository.observeMessages("desktopbot_redditbot") } returns storedMessages
        coEvery { gateway.startRun(any(), any(), any(), any()) } returns "run_abc"
        every { gateway.streamRunEvents(any(), any()) } returns flowOf(
            GatewayEvent.MessageDelta("Disk is fine."),
        )
        val vm = viewModel()
        advanceUntilIdle()
        vm.select("redditbot")
        advanceUntilIdle()

        vm.send("disk status?")
        advanceUntilIdle()

        // Storing the question makes Room re-query, and that snapshot — which has no reply in
        // it yet — can land after the reply has already streamed in. It must not win.
        storedMessages.value = listOf(storedMessage(MessageRole.USER, "disk status?"))
        advanceUntilIdle()
        assertEquals("Disk is fine.", vm.state.value.turns.single().reply)

        // Once the reply is stored too, the in-flight turn is let go without leaving a duplicate.
        storedMessages.value = listOf(
            storedMessage(MessageRole.USER, "disk status?"),
            storedMessage(MessageRole.ASSISTANT, "Disk is fine."),
        )
        advanceUntilIdle()
        assertNull(vm.state.value.pending)
        assertEquals("Disk is fine.", vm.state.value.turns.single().reply)
    }

    private fun localBot(id: String) = LocalBot(id = id, name = id, systemPrompt = "p", createdAt = 0L)

    @Test
    fun `a desktop bot is online when its profile answers and offline when it does not`() = runTest(dispatcher) {
        coEvery { gateway.listJobsByProfile(any()) } returns listOf(
            "default" to Result.success(listOf(job)),
            "redditbot" to Result.failure(java.io.IOException("timed out")),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(true, vm.state.value.online["default"])
        assertEquals(false, vm.state.value.online["redditbot"])
    }

    @Test
    fun `every desktop bot is offline while the gateway is not set up`() = runTest(dispatcher) {
        every { settings.observe() } returns MutableStateFlow(UserSettings())
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(false, vm.state.value.online["default"])
        assertEquals(false, vm.state.value.online["redditbot"])
    }

    @Test
    fun `a local bot is online exactly when the on-device model is present`() = runTest(dispatcher) {
        every { localBotStore.bots } returns MutableStateFlow(listOf(localBot("localbot_1")))
        coEvery { localLlmManager.isModelDownloaded() } returns true
        val ready = viewModel()
        advanceUntilIdle()
        assertEquals(true, ready.state.value.online["localbot_1"])

        coEvery { localLlmManager.isModelDownloaded() } returns false
        val missing = viewModel()
        advanceUntilIdle()
        assertEquals(false, missing.state.value.online["localbot_1"])
    }

    @Test
    fun `nothing is reported online or offline before the first check`() = runTest(dispatcher) {
        val vm = viewModel()

        // Nothing has run yet: absent means unknown, which the tab shows as grey, not red.
        assertNull(vm.state.value.online["default"])
    }

    @Test
    fun `a background poll refreshes availability without flashing the dashboard`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        coEvery { gateway.listJobsByProfile(any()) } returns listOf("default" to Result.failure(java.io.IOException("down")))

        vm.refreshDashboard(quiet = true)
        // Not yet run: a quiet refresh must not have switched the dashboard into loading.
        assertFalse(vm.state.value.dashboardLoading)
        advanceUntilIdle()

        assertEquals(false, vm.state.value.online["default"])
        assertFalse(vm.state.value.dashboardLoading)
    }

    @Test
    fun `stopping a PC bot's reply ends the run on the PC and clears the turn`() = runTest(dispatcher) {
        coEvery { gateway.startRun(any(), any(), any(), any()) } returns "run_abc"
        every { gateway.streamRunEvents(any(), any()) } returns flow {
            emit(GatewayEvent.MessageDelta("Working"))
            awaitCancellation()
        }
        val vm = viewModel()
        advanceUntilIdle()
        vm.select("redditbot")
        advanceUntilIdle()
        vm.send("a long task")
        advanceUntilIdle()
        assertTrue(vm.state.value.sending)

        vm.cancel()
        advanceUntilIdle()

        // Cancelling only the phone's collection would leave the run going, and spending, on
        // the PC.
        coVerify { gateway.stopRun("run_abc", "redditbot") }
        assertFalse(vm.state.value.sending)
        assertNull(vm.state.value.pending)
    }

    private fun approvalEvent() =
        GatewayEvent.ApprovalRequested("call_1", "terminal", "{\"command\":\"ls\"}", "req_1")

    @Test
    fun `a PC run that asks for approval is put to the user and answered`() = runTest(dispatcher) {
        coEvery { gateway.startRun(any(), any(), any(), any()) } returns "run_abc"
        every { gateway.streamRunEvents(any(), any()) } returns flowOf(
            approvalEvent(),
            GatewayEvent.MessageDelta("Listed."),
        )
        coEvery { toolConfirmationService.awaitConfirmation(any()) } returns true
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("what is on my desktop?")
        advanceUntilIdle()

        // Ignoring it would leave the run waiting on the PC and this chat spinning.
        coVerify {
            toolConfirmationService.awaitConfirmation(match { it.name == "terminal" && it.id == "call_1" })
        }
        coVerify { gateway.submitApproval("run_abc", true, "req_1", null) }
        assertEquals("Listed.", vm.state.value.turns.single().reply)
    }

    @Test
    fun `a refusal is passed back to the PC as a refusal`() = runTest(dispatcher) {
        coEvery { gateway.startRun(any(), any(), any(), any()) } returns "run_abc"
        every { gateway.streamRunEvents(any(), any()) } returns flowOf(approvalEvent())
        coEvery { toolConfirmationService.awaitConfirmation(any()) } returns false
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("delete the old builds")
        advanceUntilIdle()

        coVerify { gateway.submitApproval("run_abc", false, "req_1", null) }
    }

    @Test
    fun `an approval is answered on the profile that started the run`() = runTest(dispatcher) {
        coEvery { gateway.startRun(any(), any(), any(), any()) } returns "run_abc"
        every { gateway.streamRunEvents(any(), any()) } returns flowOf(approvalEvent())
        coEvery { toolConfirmationService.awaitConfirmation(any()) } returns true
        val vm = viewModel()
        advanceUntilIdle()
        vm.select("redditbot")
        advanceUntilIdle()

        vm.send("post it")
        advanceUntilIdle()

        // Sent to the default endpoint it would be rejected, and the run would wait forever.
        coVerify { gateway.submitApproval("run_abc", true, "req_1", "redditbot") }
    }

    // ── the Chief of Bots: one bot, two brains ───────────────────────────────────────────────

    private fun phoneReplies(text: String = "Done.") {
        every { chatRepository.sendMessageOrchestrated(any(), any(), any(), any(), any()) } returns
            flowOf(OrchestratorEvent.ReplyToken(text))
    }

    private fun pcReplies(text: String = "On it.") {
        coEvery { gateway.startRun(any(), any(), any(), any()) } returns "run_abc"
        every { gateway.streamRunEvents(any(), any()) } returns flowOf(GatewayEvent.MessageDelta(text))
    }

    @Test
    fun `the gateway's default bot is the hybrid Chief of Bots and keeps its id`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val chief = vm.state.value.bots.first { it.id == "default" }
        assertEquals("Chief of Bots", chief.label)
        assertEquals(BotKind.HYBRID, chief.kind)
        assertTrue(chief.isHybrid)
        assertFalse(chief.isLocal)
        // Only the default bot is renamed, and only it is a hybrid.
        val other = vm.state.value.bots.first { it.id == "redditbot" }
        assertEquals("redditbot", other.label)
        assertEquals(BotKind.DESKTOP, other.kind)
    }

    @Test
    fun `the Chief has a phone side and a PC side, a PC bot only the PC's`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.bots.first { it.id == "default" }.hasPhone)
        assertFalse(vm.state.value.bots.first { it.id == "redditbot" }.hasPhone)
    }

    @Test
    fun `renaming the Chief relabels it and is written to the store`() = runTest(dispatcher) {
        val names = MutableStateFlow("Chief of Bots")
        every { localBotStore.chiefName } returns names
        val vm = viewModel()
        advanceUntilIdle()

        names.value = "Jarvis"
        advanceUntilIdle()

        assertEquals("Jarvis", vm.state.value.bots.first { it.id == "default" }.label)
        assertEquals("Jarvis", vm.state.value.chiefName)

        vm.renameChief("Nova")
        verify { localBotStore.setChiefName("Nova") }
    }

    @Test
    fun `the dashboard shows the Chief by name, as a hybrid`() = runTest(dispatcher) {
        coEvery { gateway.listJobsByProfile(any()) } returns listOf(
            "default" to Result.success(listOf(job)),
            "redditbot" to Result.success(listOf(job)),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("Chief of Bots", "redditbot"), vm.state.value.dashboard.map { it.label })
        assertEquals(listOf(BotKind.HYBRID, BotKind.DESKTOP), vm.state.value.dashboard.map { it.kind })
    }

    @Test
    fun `every run to the Chief carries its charter, with its name`() = runTest(dispatcher) {
        pcReplies()
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("clear my inbox")
        advanceUntilIdle()

        // The session and profile are unchanged from before the rename: `default` is the
        // gateway's own name for the agent, and its thread is keyed on it.
        coVerify {
            gateway.startRun("clear my inbox", "phone-bot-default", null, ChiefOfBots.charter("Chief of Bots"))
        }
    }

    @Test
    fun `a renamed Chief is told its new name on the PC`() = runTest(dispatcher) {
        every { localBotStore.chiefName } returns MutableStateFlow("Jarvis")
        pcReplies()
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("clear my inbox")
        advanceUntilIdle()

        coVerify { gateway.startRun("clear my inbox", "phone-bot-default", null, ChiefOfBots.charter("Jarvis")) }
    }

    @Test
    fun `no other bot is sent the Chief's charter`() = runTest(dispatcher) {
        pcReplies("ok")
        val vm = viewModel()
        advanceUntilIdle()
        vm.select("redditbot")
        advanceUntilIdle()

        vm.send("draft the 9am post")
        advanceUntilIdle()

        coVerify { gateway.startRun("draft the 9am post", "phone-bot-redditbot", "redditbot", null) }
        coVerify(exactly = 0) { gateway.startRun(any(), any(), any(), ChiefOfBots.charter("Chief of Bots")) }
    }

    @Test
    fun `ordinary work goes to the Chief's PC side and not the phone's`() = runTest(dispatcher) {
        pcReplies()
        phoneReplies()
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("draft a reply to Sam")
        advanceUntilIdle()

        coVerify { gateway.startRun("draft a reply to Sam", any(), any(), any()) }
        verify(exactly = 0) { chatRepository.sendMessageOrchestrated(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a request to create a bot goes to the Chief's phone side`() = runTest(dispatcher) {
        pcReplies()
        phoneReplies("Created Scribe.")
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("create a bot for meeting notes")
        advanceUntilIdle()

        // Only the phone can create a bot on the phone, and it does so in the Chief's own thread.
        verify {
            chatRepository.sendMessageOrchestrated(
                ChiefOfBots.THREAD, "create a bot for meeting notes", ExecutionOrigin.INTERACTIVE, null, null,
            )
        }
        coVerify(exactly = 0) { gateway.startRun(any(), any(), any(), any()) }
        assertEquals("Created Scribe.", vm.state.value.turns.single().reply)
    }

    @Test
    fun `asking which bots exist is answered from the app's own lists, with no model`() = runTest(dispatcher) {
        every { localBotStore.bots } returns MutableStateFlow(listOf(localBot("PineappleBot")))
        pcReplies()
        phoneReplies("ManageBots and Todo")
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("list all my bots")
        advanceUntilIdle()

        // The on-device model, asked, invented two bots. The app knows the real ones.
        val reply = vm.state.value.turns.single().reply
        assertEquals("On this phone: PineappleBot.\nOn the PC (added here): Chief of Bots (me), redditbot.", reply)
        verify(exactly = 0) { chatRepository.sendMessageOrchestrated(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { gateway.startRun(any(), any(), any(), any()) }
        // Stored like any other turn, so the thread reads back the same way after a restart.
        coVerify {
            conversationRepository.addMessage(
                ChiefOfBots.THREAD,
                match { it.role == MessageRole.USER && it.content == "list all my bots" },
            )
        }
        coVerify {
            conversationRepository.addMessage(ChiefOfBots.THREAD, match { it.role == MessageRole.ASSISTANT && it.content == reply })
        }
        assertFalse(vm.state.value.sending)
    }

    @Test
    fun `the list follows a rename and says when the phone has no bots`() = runTest(dispatcher) {
        every { localBotStore.chiefName } returns MutableStateFlow("Jarvis")
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("what bots do I have?")
        advanceUntilIdle()

        assertEquals("On this phone: none yet.\nOn the PC (added here): Jarvis (me), redditbot.", vm.state.value.turns.single().reply)
    }

    // ── threads: history and new chat ────────────────────────────────────────────────────────

    private fun stored(conversationId: String, vararg text: String) = text.mapIndexed { i, t ->
        Message(
            id = "m$i", conversationId = conversationId,
            role = if (i % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
            content = t, timestamp = 1L + i, tokens = 1, isOnDevice = false,
        )
    }

    @Test
    fun `it can be created when its coroutines start at once, as on the real main thread`() {
        // Main.immediate runs the init block's launches during construction; a property declared
        // below `init` was still null then, and opening Bots crashed the app.
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())

        val vm = viewModel()

        assertEquals(ChiefOfBots.PROFILE, vm.state.value.selected)
    }

    @Test
    fun `new chat does nothing while the chat on screen is empty`() = runTest(dispatcher) {
        every { conversationRepository.observeMessages(any()) } returns flowOf(emptyList())
        val vm = viewModel()
        advanceUntilIdle()

        vm.newThread()
        advanceUntilIdle()

        coVerify(exactly = 0) { conversationRepository.ensureConversation(match { it.contains("#") }, any()) }
    }

    @Test
    fun `new chat starts a fresh thread that the next message goes to`() = runTest(dispatcher) {
        every { conversationRepository.observeMessages(ChiefOfBots.THREAD) } returns flowOf(stored(ChiefOfBots.THREAD, "hi", "hello"))
        every { conversationRepository.observeMessages(match { it != ChiefOfBots.THREAD }) } returns flowOf(emptyList())
        phoneReplies("ok")
        val vm = viewModel()
        advanceUntilIdle()

        vm.newThread()
        advanceUntilIdle()

        val thread = slot<String>()
        coVerify { conversationRepository.ensureConversation(capture(thread), "New chat") }
        assertTrue(thread.captured.startsWith("${ChiefOfBots.THREAD}#"))
        assertTrue(vm.state.value.turns.isEmpty())

        vm.send("create a bot for my reading list")
        advanceUntilIdle()

        verify { chatRepository.sendMessageOrchestrated(thread.captured, any(), any(), any(), any()) }
        // The first message names the thread.
        coVerify { conversationRepository.renameConversation(thread.captured, "create a bot for my reading list") }
    }

    @Test
    fun `an earlier thread can be opened again, the first included`() = runTest(dispatcher) {
        every { conversationRepository.observeMessages(ChiefOfBots.THREAD) } returns flowOf(stored(ChiefOfBots.THREAD, "hi", "hello"))
        every { conversationRepository.observeMessages(match { it != ChiefOfBots.THREAD }) } returns flowOf(stored("x", "later", "reply"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.newThread()
        advanceUntilIdle()
        assertEquals("later", vm.state.value.turns.single().sent)

        vm.openThread(ChiefOfBots.THREAD)
        advanceUntilIdle()

        assertEquals("hi", vm.state.value.turns.single().sent)
    }

    @Test
    fun `a thread of another bot cannot be opened here`() = runTest(dispatcher) {
        every { conversationRepository.observeMessages(any()) } returns flowOf(stored(ChiefOfBots.THREAD, "hi", "hello"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.openThread("desktopbot_redditbot#abc")
        advanceUntilIdle()

        assertEquals("hi", vm.state.value.turns.single().sent)
    }

    @Test
    fun `a new thread with the PC is a new session there, so it does not remember the last one`() = runTest(dispatcher) {
        every { conversationRepository.observeMessages(ChiefOfBots.THREAD) } returns flowOf(stored(ChiefOfBots.THREAD, "hi", "hello"))
        every { conversationRepository.observeMessages(match { it != ChiefOfBots.THREAD }) } returns flowOf(emptyList())
        pcReplies()
        val vm = viewModel()
        advanceUntilIdle()
        vm.newThread()
        advanceUntilIdle()

        vm.send("what's on my calendar tomorrow?")
        advanceUntilIdle()

        coVerify { gateway.startRun(any(), sessionId = match { it.startsWith("phone-bot-default-") }, profile = any(), instructions = any()) }
    }

    // ── offering the desktop's bots ──────────────────────────────────────────────────────────

    private val url = "http://pc.tailnet.ts.net:8642"

    private fun onlyDefaultKnown() {
        every { profileStore.profiles } returns MutableStateFlow(listOf("default"))
    }

    @Test
    fun `connecting to a desktop it has not seen offers the bots the phone lacks`() = runTest(dispatcher) {
        onlyDefaultKnown()
        every { profileStore.offeredGateway } returns ""
        coEvery { gateway.listProfiles() } returns listOf(
            RemoteProfile("default"), RemoteProfile("redditbot", "Reddit", "Drafts posts"),
        )
        viewModel()
        advanceUntilIdle()

        verify { profileStore.offeredGateway = url }
        verify { profileStore.openOffer = listOf("redditbot") }
        coVerify {
            conversationRepository.addMessage(
                ChiefOfBots.THREAD,
                match { it.role == MessageRole.ASSISTANT && it.content.contains("redditbot") && it.content.contains("Drafts posts") },
            )
        }
    }

    @Test
    fun `a desktop it has already looked over is not offered again`() = runTest(dispatcher) {
        onlyDefaultKnown()
        every { profileStore.offeredGateway } returns url
        viewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { gateway.listProfiles() }
    }

    @Test
    fun `nothing new on the desktop means no message, but it is marked as looked at`() = runTest(dispatcher) {
        every { profileStore.offeredGateway } returns ""
        coEvery { gateway.listProfiles() } returns listOf(RemoteProfile("default"), RemoteProfile("redditbot"))
        viewModel()
        advanceUntilIdle()

        verify { profileStore.offeredGateway = url }
        coVerify(exactly = 0) { conversationRepository.addMessage(ChiefOfBots.THREAD, any()) }
    }

    @Test
    fun `an unreachable desktop is not marked, so the next launch tries again`() = runTest(dispatcher) {
        every { profileStore.offeredGateway } returns ""
        coEvery { gateway.listProfiles() } throws java.io.IOException("timeout")
        viewModel()
        advanceUntilIdle()

        verify(exactly = 0) { profileStore.offeredGateway = any() }
        coVerify(exactly = 0) { conversationRepository.addMessage(ChiefOfBots.THREAD, any()) }
    }

    @Test
    fun `an older gateway that cannot list bots is asked about common names`() = runTest(dispatcher) {
        onlyDefaultKnown()
        every { profileStore.offeredGateway } returns ""
        coEvery { gateway.listProfiles() } returns null
        coEvery { gateway.profileExists(any()) } returns false
        coEvery { gateway.profileExists("redditbot") } returns true
        viewModel()
        advanceUntilIdle()

        verify { profileStore.openOffer = listOf("redditbot") }
    }

    @Test
    fun `yes to the offer adds the bots, with no model involved`() = runTest(dispatcher) {
        every { profileStore.openOffer } returns listOf("redditbot", "coder")
        every { profileStore.add(any()) } returns true
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("yes")
        advanceUntilIdle()

        verify { profileStore.add("redditbot") }
        verify { profileStore.add("coder") }
        verify { profileStore.openOffer = emptyList() }
        assertEquals("Added redditbot, coder. They're in your bot tabs.", vm.state.value.turns.single().reply)
        coVerify(exactly = 0) { gateway.startRun(any(), any(), any(), any()) }
        verify(exactly = 0) { chatRepository.sendMessageOrchestrated(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `no to the offer adds nothing and closes it`() = runTest(dispatcher) {
        every { profileStore.openOffer } returns listOf("redditbot")
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("no thanks")
        advanceUntilIdle()

        verify(exactly = 0) { profileStore.add(any()) }
        verify { profileStore.openOffer = emptyList() }
        assertTrue(vm.state.value.turns.single().reply.startsWith("Okay, I'll leave them out."))
    }

    @Test
    fun `naming one bot adds it and keeps the rest on offer`() = runTest(dispatcher) {
        every { profileStore.openOffer } returns listOf("redditbot", "coder")
        every { profileStore.add(any()) } returns true
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("add coder")
        advanceUntilIdle()

        verify { profileStore.add("coder") }
        verify(exactly = 0) { profileStore.add("redditbot") }
        verify { profileStore.openOffer = listOf("redditbot") }
        assertTrue(vm.state.value.turns.single().reply.contains("Still on offer: redditbot"))
    }

    @Test
    fun `a message that is not an answer goes on as normal while an offer is open`() = runTest(dispatcher) {
        every { profileStore.openOffer } returns listOf("redditbot")
        pcReplies()
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("what's on my calendar tomorrow?")
        advanceUntilIdle()

        verify(exactly = 0) { profileStore.add(any()) }
        coVerify { gateway.startRun(any(), any(), any(), any()) }
    }

    @Test
    fun `asking the Chief to look again makes the offer on request`() = runTest(dispatcher) {
        onlyDefaultKnown()
        every { profileStore.offeredGateway } returns url
        coEvery { gateway.listProfiles() } returns listOf(RemoteProfile("default"), RemoteProfile("redditbot"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("find the bots on my PC")
        advanceUntilIdle()

        assertTrue(vm.state.value.turns.single().reply.contains("redditbot"))
        assertTrue(vm.state.value.turns.single().reply.contains("Want me to add it?"))
    }

    // ── creating and removing bots: done by the app, on the user's word ──────────────────────

    @Test
    fun `a clear create request makes the bot itself, without a model`() = runTest(dispatcher) {
        coEvery { toolConfirmationService.awaitConfirmation(any()) } returns true
        every { localBotStore.add(any(), any(), any()) } returns localBot("Scribe")
        phoneReplies("should not be used")
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("Create a bot named Scribe that takes meeting notes")
        advanceUntilIdle()

        verify { localBotStore.add("Scribe", "You are Scribe. Your job: takes meeting notes.", false) }
        assertEquals("Created “Scribe”. It's in your bot tabs.", vm.state.value.turns.single().reply)
        // The small model, asked to do this, did not. Nothing here needs it.
        verify(exactly = 0) { chatRepository.sendMessageOrchestrated(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { gateway.startRun(any(), any(), any(), any()) }
        coVerify {
            conversationRepository.addMessage(ChiefOfBots.THREAD, match { it.role == MessageRole.USER })
        }
        assertFalse(vm.state.value.sending)
    }

    @Test
    fun `it is asked to confirm first, exactly as the tool call would have been`() = runTest(dispatcher) {
        coEvery { toolConfirmationService.awaitConfirmation(any()) } returns true
        every { localBotStore.add(any(), any(), any()) } returns localBot("Scribe")
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("create a bot named Scribe")
        advanceUntilIdle()

        coVerify {
            toolConfirmationService.awaitConfirmation(match { it.name == "manage_bots" })
        }
    }

    @Test
    fun `a refusal leaves everything as it was`() = runTest(dispatcher) {
        coEvery { toolConfirmationService.awaitConfirmation(any()) } returns false
        every { localBotStore.bots } returns MutableStateFlow(listOf(localBot("Scribe")))
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("Remove the bot named scribe")
        advanceUntilIdle()

        assertEquals("Okay, I left it alone.", vm.state.value.turns.single().reply)
        verify(exactly = 0) { localBotStore.remove(any()) }
        verify(exactly = 0) { localBotStore.add(any(), any(), any()) }
    }

    @Test
    fun `a clear remove request removes the bot, whatever case it was named in`() = runTest(dispatcher) {
        coEvery { toolConfirmationService.awaitConfirmation(any()) } returns true
        every { localBotStore.bots } returns MutableStateFlow(listOf(localBot("Scribe"), localBot("PineappleBot")))
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("Remove the bot named scribe")
        advanceUntilIdle()

        verify { localBotStore.remove("Scribe") }
        verify(exactly = 0) { localBotStore.remove("PineappleBot") }
        assertEquals("Removed “Scribe”.", vm.state.value.turns.single().reply)
    }

    @Test
    fun `removing a bot that is not there says so and removes nothing`() = runTest(dispatcher) {
        coEvery { toolConfirmationService.awaitConfirmation(any()) } returns true
        every { localBotStore.bots } returns MutableStateFlow(listOf(localBot("PineappleBot")))
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("delete the bot called ghost")
        advanceUntilIdle()

        assertEquals("There's no bot named “ghost” on this phone.", vm.state.value.turns.single().reply)
        verify(exactly = 0) { localBotStore.remove(any()) }
    }

    @Test
    fun `creating a bot whose name is taken says so`() = runTest(dispatcher) {
        coEvery { toolConfirmationService.awaitConfirmation(any()) } returns true
        every { localBotStore.add(any(), any(), any()) } returns null
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("create a bot named Scribe")
        advanceUntilIdle()

        assertEquals("I couldn't create “Scribe”: that name is already taken.", vm.state.value.turns.single().reply)
    }

    @Test
    fun `a looser request is still left to the model`() = runTest(dispatcher) {
        coEvery { toolConfirmationService.awaitConfirmation(any()) } returns true
        phoneReplies("What should it do?")
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("create a bot for my reading list")
        advanceUntilIdle()

        verify { chatRepository.sendMessageOrchestrated(ChiefOfBots.THREAD, any(), any(), any(), any()) }
        verify(exactly = 0) { localBotStore.add(any(), any(), any()) }
    }

    @Test
    fun `a request that changes bots still goes to the model, not the list`() = runTest(dispatcher) {
        every { localBotStore.bots } returns MutableStateFlow(listOf(localBot("PineappleBot")))
        phoneReplies("Removed.")
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("list my bots and remove the pineapple bot")
        advanceUntilIdle()

        verify { chatRepository.sendMessageOrchestrated(ChiefOfBots.THREAD, any(), any(), any(), any()) }
        assertEquals("Removed.", vm.state.value.turns.single().reply)
    }

    @Test
    fun `an attachment goes to the Chief's phone side because the PC takes text alone`() = runTest(dispatcher) {
        pcReplies()
        phoneReplies("A cat.")
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("what is this?", "content://media/1", "image/png")
        advanceUntilIdle()

        verify {
            chatRepository.sendMessageOrchestrated(
                ChiefOfBots.THREAD, "what is this?", ExecutionOrigin.INTERACTIVE, "content://media/1", "image/png",
            )
        }
        coVerify(exactly = 0) { gateway.startRun(any(), any(), any(), any()) }
    }

    @Test
    fun `the Chief answers on the phone when the PC is known to be down`() = runTest(dispatcher) {
        coEvery { gateway.listJobsByProfile(any()) } returns
            listOf("default" to Result.failure(java.io.IOException("down")))
        phoneReplies("I am here.")
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(false, vm.state.value.chiefPcOnline)

        vm.send("are you there?")
        advanceUntilIdle()

        // Not even tried: it goes straight to the phone.
        coVerify(exactly = 0) { gateway.startRun(any(), any(), any(), any()) }
        verify { chatRepository.sendMessageOrchestrated(ChiefOfBots.THREAD, "are you there?", any(), any(), any()) }
    }

    @Test
    fun `the Chief falls back to the phone when the PC turns out to be unreachable`() = runTest(dispatcher) {
        coEvery { gateway.startRun(any(), any(), any(), any()) } throws java.io.IOException("connection refused")
        phoneReplies("I can help from the phone.")
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("what is on my calendar?")
        advanceUntilIdle()

        verify { chatRepository.sendMessageOrchestrated(ChiefOfBots.THREAD, "what is on my calendar?", any(), any(), any()) }
        assertEquals("I can help from the phone.", vm.state.value.turns.single().reply)
        // Remembered, so the next message does not try the PC first...
        assertEquals(false, vm.state.value.chiefPcOnline)
        // ...and said, so the user knows why the answer is a phone's.
        assertTrue(vm.state.value.error!!.contains("answered on the phone"))
        // The question is stored once, by the phone side — not also by the failed PC attempt.
        coVerify(exactly = 0) {
            conversationRepository.addMessage(any(), match { it.role == MessageRole.USER })
        }
    }

    @Test
    fun `both of the Chief's brains write to the one thread`() = runTest(dispatcher) {
        pcReplies("On it.")
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("clear my inbox")
        advanceUntilIdle()

        coVerify { conversationRepository.ensureConversation(ChiefOfBots.THREAD, "Chief of Bots") }
        coVerify {
            conversationRepository.addMessage(
                ChiefOfBots.THREAD,
                match { it.role == MessageRole.USER && it.content == "clear my inbox" },
            )
        }
        coVerify {
            conversationRepository.addMessage(
                ChiefOfBots.THREAD,
                match { it.role == MessageRole.ASSISTANT && it.content == "On it." },
            )
        }
    }

    @Test
    fun `the Chief's thread is read back from that one conversation`() = runTest(dispatcher) {
        every { conversationRepository.observeMessages(ChiefOfBots.THREAD) } returns MutableStateFlow(
            listOf(
                Message(id = "1", conversationId = ChiefOfBots.THREAD, role = MessageRole.USER, content = "hi", timestamp = 0L),
                Message(id = "2", conversationId = ChiefOfBots.THREAD, role = MessageRole.ASSISTANT, content = "hello", timestamp = 1L),
            ),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(BotTurn("hi", "hello")), vm.state.value.turns)
    }

    // ── the Chief's availability: either side answering is enough ────────────────────────────

    @Test
    fun `the Chief is available while only its phone side answers`() = runTest(dispatcher) {
        coEvery { gateway.listJobsByProfile(any()) } returns listOf(
            "default" to Result.failure(java.io.IOException("down")),
            "redditbot" to Result.failure(java.io.IOException("down")),
        )
        coEvery { localLlmManager.isModelDownloaded() } returns true
        val vm = viewModel()
        advanceUntilIdle()

        // The PC is down, but the phone can still answer: the Chief is up and a PC bot is not.
        assertEquals(true, vm.state.value.online["default"])
        assertEquals(false, vm.state.value.online["redditbot"])
        assertEquals(false, vm.state.value.chiefPcOnline)
    }

    @Test
    fun `the Chief is available while only its PC side answers`() = runTest(dispatcher) {
        coEvery { gateway.listJobsByProfile(any()) } returns listOf("default" to Result.success(listOf(job)))
        coEvery { localLlmManager.isModelDownloaded() } returns false
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(true, vm.state.value.online["default"])
        assertEquals(true, vm.state.value.chiefPcOnline)
    }

    @Test
    fun `the Chief is unavailable only when neither side answers`() = runTest(dispatcher) {
        coEvery { gateway.listJobsByProfile(any()) } returns
            listOf("default" to Result.failure(java.io.IOException("down")))
        coEvery { localLlmManager.isModelDownloaded() } returns false
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(false, vm.state.value.online["default"])
    }

    @Test
    fun `an unconfigured gateway leaves the Chief with its phone side`() = runTest(dispatcher) {
        every { settings.observe() } returns MutableStateFlow(UserSettings())
        coEvery { localLlmManager.isModelDownloaded() } returns true
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(true, vm.state.value.online["default"])
        assertEquals(false, vm.state.value.chiefPcOnline)
        assertTrue(vm.state.value.dashboard.first().status.contains("answering on the phone"))
    }

    @Test
    fun `an unreachable PC is reported on the Chief's dashboard card as the phone answering`() = runTest(dispatcher) {
        coEvery { gateway.listJobsByProfile(any()) } returns
            listOf("default" to Result.failure(java.io.IOException("down")))
        coEvery { localLlmManager.isModelDownloaded() } returns true
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("PC unreachable — answering on the phone", vm.state.value.dashboard.first().status)
    }

    @Test
    fun `a stopped reply is not reported as a failure`() = runTest(dispatcher) {
        coEvery { gateway.startRun(any(), any(), any(), any()) } returns "run_abc"
        every { gateway.streamRunEvents(any(), any()) } returns flow { awaitCancellation() }
        val vm = viewModel()
        advanceUntilIdle()
        vm.send("hello")
        advanceUntilIdle()

        vm.cancel()
        advanceUntilIdle()

        assertNull(vm.state.value.error)
        assertNull(vm.state.value.pending)
    }

    @Test
    fun `an attachment is passed to a local bot`() = runTest(dispatcher) {
        every { localBotStore.bots } returns MutableStateFlow(listOf(localBot("localbot_1")))
        every { chatRepository.sendMessageOrchestrated(any(), any(), any(), any(), any()) } returns
            flowOf(OrchestratorEvent.ReplyToken("Nice picture"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.select("localbot_1")
        advanceUntilIdle()

        vm.send("look at this", "content://media/1", "image/png")
        advanceUntilIdle()

        verify {
            chatRepository.sendMessageOrchestrated(
                "localbot_1", "look at this", ExecutionOrigin.INTERACTIVE, "content://media/1", "image/png",
            )
        }
    }

    @Test
    fun `an image with no text can still be sent`() = runTest(dispatcher) {
        every { localBotStore.bots } returns MutableStateFlow(listOf(localBot("localbot_1")))
        every { chatRepository.sendMessageOrchestrated(any(), any(), any(), any(), any()) } returns
            flowOf(OrchestratorEvent.ReplyToken("A cat"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.select("localbot_1")
        advanceUntilIdle()

        vm.send("", "content://media/2", "image/png")
        advanceUntilIdle()

        verify { chatRepository.sendMessageOrchestrated("localbot_1", "", any(), "content://media/2", "image/png") }
    }

    @Test
    fun `the reasoning effort follows the app setting, and changing it writes that setting`() = runTest(dispatcher) {
        every { settings.observe() } returns MutableStateFlow(
            UserSettings(
                remoteGatewayUrl = "http://pc.tailnet.ts.net:8642",
                remoteGatewayApiKey = "k".repeat(32),
                reasoningEffort = "high",
            ),
        )
        coEvery { settings.setReasoningEffort(any()) } returns Unit
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("high", vm.state.value.reasoningEffort)

        vm.setReasoningEffort("low")
        advanceUntilIdle()

        coVerify { settings.setReasoningEffort("low") }
    }

    @Test
    fun `dictation fills the composer and leaves the sending to you`() = runTest(dispatcher) {
        every { voiceInput.isAvailable() } returns true
        every { voiceInput.listen(any()) } returns
            flowOf(VoiceInputEvent.Partial("hel"), VoiceInputEvent.Final("hello there"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.toggleVoiceInput()
        advanceUntilIdle()

        assertEquals("hello there", vm.state.value.inputPrefill)
        assertFalse(vm.state.value.isListening)
        coVerify(exactly = 0) { gateway.startRun(any(), any(), any(), any()) }
    }

    @Test
    fun `dictation says so when there is no speech recogniser`() = runTest(dispatcher) {
        every { voiceInput.isAvailable() } returns false
        val vm = viewModel()
        advanceUntilIdle()

        vm.toggleVoiceInput()
        advanceUntilIdle()

        assertTrue(vm.state.value.error!!.contains("not available"))
        assertFalse(vm.state.value.isListening)
    }

    @Test
    fun `voice chat sends what was said, reads the answer aloud, then listens again`() = runTest(dispatcher) {
        every { voiceInput.isAvailable() } returns true
        // What is said once, then only silence.
        every { voiceInput.listen(any()) } returnsMany listOf(
            flowOf(VoiceInputEvent.Final("ping")),
            flowOf(VoiceInputEvent.Final("")),
        )
        every { voiceOutput.isAvailable() } returns true
        every { voiceOutput.speak(any(), any()) } returns
            flowOf(VoiceOutputEvent.Start, VoiceOutputEvent.Done)
        coEvery { gateway.startRun("ping", any(), any(), any()) } returns "run_abc"
        every { gateway.streamRunEvents("run_abc", any()) } returns flowOf(GatewayEvent.MessageDelta("pong"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.toggleVoiceChat()
        advanceUntilIdle()

        coVerify { gateway.startRun("ping", any(), any(), any()) }
        verify { voiceOutput.speak("pong", any()) }
        // Three turns of silence and it gives up instead of spinning on a dead microphone.
        assertFalse(vm.state.value.voiceChatActive)
        assertTrue(vm.state.value.error!!.contains("couldn't hear"))
    }

    @Test
    fun `switching bots ends voice chat rather than carrying it to the other bot`() = runTest(dispatcher) {
        every { voiceInput.isAvailable() } returns true
        // Never finishes, so the loop stays open until it is stopped.
        every { voiceInput.listen(any()) } returns flow { awaitCancellation() }
        val vm = viewModel()
        advanceUntilIdle()
        vm.toggleVoiceChat()
        advanceUntilIdle()
        assertTrue(vm.state.value.voiceChatActive)

        vm.select("redditbot")
        advanceUntilIdle()

        assertFalse(vm.state.value.voiceChatActive)
        assertFalse(vm.state.value.isListening)
    }

    @Test
    fun `a stored question with an image is shown as one with an attachment`() = runTest(dispatcher) {
        every { localBotStore.bots } returns MutableStateFlow(listOf(localBot("localbot_1")))
        every { conversationRepository.observeMessages("localbot_1") } returns MutableStateFlow(
            listOf(
                Message(
                    id = "m1", conversationId = "localbot_1", role = MessageRole.USER,
                    content = "look", timestamp = 0L, attachmentUri = "content://media/1",
                ),
            ),
        )
        val vm = viewModel()
        advanceUntilIdle()
        vm.select("localbot_1")
        advanceUntilIdle()

        assertTrue(vm.state.value.turns.single().attachment)
    }

    @Test
    fun `a failed desktop run stores the question but no reply`() = runTest(dispatcher) {
        coEvery { gateway.startRun(any(), any(), any(), any()) } returns "run_abc"
        every { gateway.streamRunEvents(any(), any()) } returns flowOf(
            GatewayEvent.RunFailed("Connection lost"),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("hello")
        advanceUntilIdle()

        coVerify(exactly = 0) {
            conversationRepository.addMessage(any(), match { it.role == MessageRole.ASSISTANT })
        }
    }

    @Test
    fun `a run that fails mid-stream marks the turn instead of leaving it blank`() = runTest(dispatcher) {
        coEvery { gateway.startRun(any(), any(), any(), any()) } returns "run_abc"
        every { gateway.streamRunEvents(any(), any()) } returns flowOf(
            GatewayEvent.RunFailed("Connection lost"),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.send("hello")
        advanceUntilIdle()

        val turn = vm.state.value.turns.single()
        assertTrue(turn.failed)
        assertEquals("Connection lost", turn.reply)
    }

    @Test
    fun `an unconfigured gateway is reported rather than queried`() = runTest(dispatcher) {
        every { settings.observe() } returns MutableStateFlow(UserSettings())
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.configured)
        assertTrue(vm.state.value.jobs.isEmpty())
        coVerify(exactly = 0) { gateway.listJobs(any()) }
    }
}
