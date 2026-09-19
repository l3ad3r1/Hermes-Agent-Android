package com.hermes.agent.data.agent

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.data.memory.ConversationLearner
import com.hermes.agent.data.memory.UserModelService
import com.hermes.agent.domain.agent.Agent
import com.hermes.agent.domain.agent.AgentRouter
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.ledger.ActivityLedger
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.agent.RoutingResult
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.ExecutionPlan
import com.hermes.agent.domain.model.StepStatus
import com.hermes.agent.domain.rag.RagPipeline
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.domain.tool.ToolConfirmationService
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.util.DispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import com.hermes.agent.domain.agent.AgentActivity
import com.hermes.agent.domain.agent.AgentPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestratorPlanPersistenceTest {

    @Test
    fun `successful turn persists plan running and succeeded transitions`() = runTest {
        val fixture = fixture(AgentLoopOutcome.Completed("answer", emptyList()))

        val events = fixture.orchestrator.run("conversation", "hello", emptyList(), ExecutionOrigin.INTERACTIVE).toList()

        val planSlot = slot<ExecutionPlan>()
        coVerify(exactly = 1) { fixture.plans.save(capture(planSlot)) }
        val stepId = planSlot.captured.steps.single().id
        coVerify(exactly = 1) { fixture.plans.markStepRunning(stepId) }
        coVerify(exactly = 1) {
            fixture.plans.markStepFinished(stepId, StepStatus.SUCCEEDED, null)
        }
        assertTrue(events.any { it is OrchestratorEvent.StepFinished && it.success })
    }

    @Test
    fun `guarded loop failure persists failed step before reporting failure`() = runTest {
        val fixture = fixture(
            AgentLoopOutcome.Failed(
                AgentLoopFailureReason.REPEATED_NO_PROGRESS,
                "stopped",
                listOf("lookup"),
            ),
        )

        val events = fixture.orchestrator.run("conversation", "hello", emptyList(), ExecutionOrigin.INTERACTIVE).toList()

        val planSlot = slot<ExecutionPlan>()
        coVerify { fixture.plans.save(capture(planSlot)) }
        coVerify {
            fixture.plans.markStepFinished(
                planSlot.captured.steps.single().id,
                StepStatus.FAILED,
                "stopped",
            )
        }
        assertTrue(events.any { it is OrchestratorEvent.StepFinished && !it.success })
        assertTrue(events.any { it is OrchestratorEvent.Failed && it.message == "stopped" })
    }

    @Test
    fun `a turn walks through the phases the orb renders`() = runTest {
        val fixture = fixture(AgentLoopOutcome.Completed("answer", emptyList()))

        fixture.orchestrator.run("conversation", "hello", emptyList(), ExecutionOrigin.INTERACTIVE)
            .toList()

        // Phases are captured inside the collaborators rather than sampled from
        // the collector. Two reasons the obvious approach does not work: phase
        // is a StateFlow, so fast transitions conflate; and `flowOn` buffers, so
        // the producer runs ahead of anything reading downstream.
        val at = fixture.phases()

        // The orb is driven off these; if the orchestrator stops reporting them
        // it silently degrades to one state and nobody notices.
        assertEquals("routing should report SOLVING", AgentPhase.SOLVING, at["routing"])
        assertEquals("retrieval should report SEARCHING", AgentPhase.SEARCHING, at["retrieval"])
        assertEquals("awaiting the model should report THINKING", AgentPhase.THINKING, at["inference"])
        assertEquals("reply text should report COMPOSING", AgentPhase.COMPOSING, at["reply"])

        // And the run must not leave the orb spinning once it is over.
        assertEquals(AgentPhase.IDLE, AgentActivity.phase.value)
    }

    @Test
    fun `a persona chat always uses the conversational agent, whatever the keywords say`() = runTest {
        // The router would send this to PRODUCTIVITY because of "notes".
        val fixture = fixture(
            AgentLoopOutcome.Completed("answer", emptyList()),
            personaChat = true,
            routerWouldPick = AgentRole.PRODUCTIVITY,
        )

        fixture.orchestrator.run(
            "chief_of_bots", "Create a bot named Scribe that takes meeting notes", emptyList(),
            ExecutionOrigin.INTERACTIVE,
        ).toList()

        coVerify(exactly = 0) { fixture.router.route(any()) }
        coVerify(atLeast = 1) { fixture.registry.get(AgentRole.CONVERSATIONAL) }
        coVerify(exactly = 0) { fixture.registry.get(AgentRole.PRODUCTIVITY) }
        val planSlot = slot<ExecutionPlan>()
        coVerify { fixture.plans.save(capture(planSlot)) }
        assertEquals(AgentRole.CONVERSATIONAL, planSlot.captured.steps.single().agentRole)
    }

    private val earlierTurns = listOf(
        LlmMessage("user", "List all my bots"),
        LlmMessage("assistant", "You have three bots on this device: 1. Jarvis 2. ManageBots 3. Todo"),
        LlmMessage("user", "Create a bot named Scribe"),
        LlmMessage("assistant", "tool_call {\"name\": \"todo\"}"),
    )

    @Test
    fun `asking the Chief to create or remove a bot gives the model no history to copy`() = runTest {
        val fixture = fixture(AgentLoopOutcome.Completed("done", emptyList()), personaChat = true)

        fixture.orchestrator.run(
            "chief_of_bots", "Remove the bot named scribe", earlierTurns, ExecutionOrigin.INTERACTIVE,
        ).toList()

        val shown = fixture.shownToModel.single()
        // Its own earlier mistakes are what it repeated; without them there is nothing to repeat.
        assertTrue(shown.none { it.content.contains("ManageBots") || it.content.contains("todo") })
        assertEquals("Remove the bot named scribe", shown.last { it.role == "user" }.content)
        assertEquals(1, shown.count { it.role == "user" })
    }

    @Test
    fun `any other message to the Chief keeps its history, minus raw tool calls`() = runTest {
        val fixture = fixture(AgentLoopOutcome.Completed("hi", emptyList()), personaChat = true)

        fixture.orchestrator.run(
            "chief_of_bots", "thanks", earlierTurns, ExecutionOrigin.INTERACTIVE,
        ).toList()

        val shown = fixture.shownToModel.single()
        assertTrue(shown.any { it.content.startsWith("You have three bots") })
        assertTrue(shown.none { it.content.startsWith("tool_call") })
    }

    @Test
    fun `a stored raw tool call is not replayed to a persona chat as history`() {
        val bare = LlmMessage("assistant", "tool_call {\"name\": \"todo\", \"arguments\": {\"action\": \"create\"}}")
        val tagged = LlmMessage("assistant", "  <tool_call>{\"name\":\"manage_bots\"}</tool_call>")
        val prose = LlmMessage("assistant", "I created the bot. The tool_call went fine.")
        val user = LlmMessage("user", "tool_call is a term I use")

        assertTrue(isRawToolCallReply(bare))
        assertTrue(isRawToolCallReply(tagged))
        // A real answer that merely mentions the words, and anything the user said, stays.
        assertEquals(false, isRawToolCallReply(prose))
        assertEquals(false, isRawToolCallReply(user))
    }

    @Test
    fun `an ordinary chat is still routed by what it says`() = runTest {
        val fixture = fixture(
            AgentLoopOutcome.Completed("answer", emptyList()),
            personaChat = false,
            routerWouldPick = AgentRole.PRODUCTIVITY,
        )

        fixture.orchestrator.run("conversation", "add milk to my todo list", emptyList(), ExecutionOrigin.INTERACTIVE).toList()

        coVerify(exactly = 1) { fixture.router.route(any()) }
        val planSlot = slot<ExecutionPlan>()
        coVerify { fixture.plans.save(capture(planSlot)) }
        assertEquals(AgentRole.PRODUCTIVITY, planSlot.captured.steps.single().agentRole)
    }

    private fun fixture(
        outcome: AgentLoopOutcome,
        personaChat: Boolean = false,
        routerWouldPick: AgentRole = AgentRole.CONVERSATIONAL,
    ): Fixture {
        // Phase observed at each stage, recorded from inside the producer
        // coroutine where it is actually accurate.
        val phases = mutableMapOf<String, AgentPhase>()
        fun mark(stage: String) { phases.putIfAbsent(stage, AgentActivity.phase.value) }

        val agentRouter = mockk<AgentRouter>()
        coEvery { agentRouter.route(any()) } coAnswers {
            mark("routing")
            RoutingResult.Solo(routerWouldPick, 1f)
        }

        val agent = mockk<Agent>()
        every { agent.systemPrompt } returns "system"
        every { agent.availableTools(any()) } returns emptyList()
        val agentRegistry = mockk<AgentRegistry>()
        every { agentRegistry.get(any()) } returns agent

        val provider = mockk<LlmProvider>(relaxed = true)
        every { provider.isOnDevice } returns true
        val llmRouter = mockk<LlmRouter>()
        val shownToModel = mutableListOf<List<LlmMessage>>()
        coEvery { llmRouter.route(any(), any()) } coAnswers {
            shownToModel += firstArg<List<LlmMessage>>()
            RoutingDecision.Ready(provider, "test")
        }

        val loopRunner = mockk<AgentLoopRunner>()
        coEvery { loopRunner.run(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            mark("inference")
            outcome
        }

        // markStepFinished(SUCCEEDED) is the first collaborator called after the
        // reply text lands, so it sits inside the COMPOSING window.
        val plans = mockk<ExecutionPlanRepository>(relaxed = true)
        coEvery { plans.markStepFinished(any(), StepStatus.SUCCEEDED, any()) } coAnswers {
            mark("reply")
        }

        val memoryRepository = mockk<MemoryRepository>(relaxed = true)
        coEvery { memoryRepository.searchMemories(any(), any()) } coAnswers {
            mark("retrieval")
            emptyList()
        }

        val deterministicRouter = mockk<DeterministicPhoneCommandRouter>()
        every { deterministicRouter.match(any<String>()) } returns null
        val orchestrator = OrchestratorImpl(
            agentRouter = agentRouter,
            agentRegistry = agentRegistry,
            toolRegistry = mockk<ToolRegistry>(relaxed = true),
            deferredToolScope = com.hermes.agent.data.tools.DeferredToolScope(),
            llmRouter = llmRouter,
            agentLoopRunner = loopRunner,
            deterministicPhoneCommandRouter = deterministicRouter,
            toolCallExecutor = mockk(relaxed = true),
            toolExecutionPolicy = com.hermes.agent.domain.tool.ToolExecutionPolicy(mockk(relaxed = true)),
            dispatchers = object : DispatcherProvider {
                override val io = Dispatchers.Unconfined
                override val default = Dispatchers.Unconfined
                override val main = Dispatchers.Unconfined
                override val unconfined = Dispatchers.Unconfined
            },
            memoryRepository = memoryRepository,
            conversationLearner = mockk<ConversationLearner>(relaxed = true),
            toolConfirmationService = mockk<ToolConfirmationService>(relaxed = true),
            autonomousSkillCreator = mockk<AutonomousSkillCreator>(relaxed = true),
            userModelService = mockk<UserModelService>(relaxed = true),
            skillMatcher = mockk<SkillMatcher>(relaxed = true),
            ragPipeline = mockk<RagPipeline>(relaxed = true),
            executionPlanRepository = plans,
            activityLedger = mockk<ActivityLedger>(relaxed = true),
            settingsRepository = mockk<com.hermes.agent.domain.settings.SettingsRepository>(relaxed = true) {
                coEvery { current() } returns com.hermes.agent.domain.settings.UserSettings()
            },
            localBotStore = mockk<com.hermes.agent.data.local.LocalBotStore>(relaxed = true) {
                every { isLocalBot(any()) } returns personaChat
            },
        )
        return Fixture(orchestrator, plans, agentRouter, agentRegistry, shownToModel) { phases.toMap() }
    }

    private data class Fixture(
        val orchestrator: OrchestratorImpl,
        val plans: ExecutionPlanRepository,
        val router: AgentRouter,
        val registry: AgentRegistry,
        val shownToModel: List<List<LlmMessage>>,
        val phases: () -> Map<String, AgentPhase>,
    )
}
