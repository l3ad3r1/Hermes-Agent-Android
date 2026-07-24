package com.hermes.agent.data.agent

import com.hermes.agent.data.llm.LlmProvider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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

    private fun fixture(outcome: AgentLoopOutcome): Fixture {
        val agentRouter = mockk<AgentRouter>()
        coEvery { agentRouter.route(any()) } returns RoutingResult.Solo(AgentRole.CONVERSATIONAL, 1f)

        val agent = mockk<Agent>()
        every { agent.systemPrompt } returns "system"
        every { agent.availableTools(any()) } returns emptyList()
        val agentRegistry = mockk<AgentRegistry>()
        every { agentRegistry.get(any()) } returns agent

        val provider = mockk<LlmProvider>(relaxed = true)
        every { provider.isOnDevice } returns true
        val llmRouter = mockk<LlmRouter>()
        coEvery { llmRouter.route(any()) } returns RoutingDecision.Ready(provider, "test")

        val loopRunner = mockk<AgentLoopRunner>()
        coEvery { loopRunner.run(any(), any(), any(), any(), any(), any(), any()) } returns outcome
        val plans = mockk<ExecutionPlanRepository>(relaxed = true)

        val orchestrator = OrchestratorImpl(
            agentRouter = agentRouter,
            agentRegistry = agentRegistry,
            toolRegistry = mockk<ToolRegistry>(relaxed = true),
            llmRouter = llmRouter,
            agentLoopRunner = loopRunner,
            dispatchers = object : DispatcherProvider {
                override val io = Dispatchers.Unconfined
                override val default = Dispatchers.Unconfined
                override val main = Dispatchers.Unconfined
                override val unconfined = Dispatchers.Unconfined
            },
            memoryRepository = mockk<MemoryRepository>(relaxed = true),
            conversationLearner = mockk<ConversationLearner>(relaxed = true),
            toolConfirmationService = mockk<ToolConfirmationService>(relaxed = true),
            autonomousSkillCreator = mockk<AutonomousSkillCreator>(relaxed = true),
            userModelService = mockk<UserModelService>(relaxed = true),
            skillMatcher = mockk<SkillMatcher>(relaxed = true),
            ragPipeline = mockk<RagPipeline>(relaxed = true),
            executionPlanRepository = plans,
            activityLedger = mockk<ActivityLedger>(relaxed = true),
        )
        return Fixture(orchestrator, plans)
    }

    private data class Fixture(
        val orchestrator: OrchestratorImpl,
        val plans: ExecutionPlanRepository,
    )
}
