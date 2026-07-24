package com.hermes.agent.data.agent

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.data.memory.ConversationLearner
import com.hermes.agent.data.memory.UserModelService
import com.hermes.agent.data.tool.ToolCallExecutor
import com.hermes.agent.domain.agent.AgentRouter
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.RoutingResult
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.ExecutionPlan
import com.hermes.agent.domain.model.ExecutionStep
import com.hermes.agent.domain.ledger.ActivityLedger
import com.hermes.agent.domain.model.ActivityEntry
import com.hermes.agent.domain.model.ActivityKind
import com.hermes.agent.domain.model.StepStatus
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.util.DispatcherProvider
import com.hermes.agent.util.IdGenerator
import com.hermes.agent.domain.agent.AgentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [Orchestrator] implementation.
 *
 * Wires together routing, agent personas, tool execution, and the
 * closed self-improvement learning loop:
 *
 *   1. Route user message → RoutingResult.
 *   2. Load memories + user model → inject into system prompt.
 *   3. Execute steps (tool-call loop per step).
 *   4. After completion, fire off [ConversationLearner] (extract new
 *      facts → memory) and [AutonomousSkillCreator] (generate skill if
 *      complex task detected) in the background [learningScope].
 *   5. Notify [UserModelService] so it can rebuild the user profile
 *      every N conversations.
 */
@Singleton
class OrchestratorImpl @Inject constructor(
    private val agentRouter: AgentRouter,
    private val agentRegistry: AgentRegistry,
    private val toolRegistry: ToolRegistry,
    private val llmRouter: LlmRouter,
    private val agentLoopRunner: AgentLoopRunner,
    private val dispatchers: DispatcherProvider,
    private val memoryRepository: MemoryRepository,
    private val conversationLearner: ConversationLearner,
    private val toolConfirmationService: com.hermes.agent.domain.tool.ToolConfirmationService,
    private val autonomousSkillCreator: AutonomousSkillCreator,
    private val userModelService: UserModelService,
    private val skillMatcher: SkillMatcher,
    private val ragPipeline: com.hermes.agent.domain.rag.RagPipeline,
    private val executionPlanRepository: ExecutionPlanRepository,
    private val activityLedger: ActivityLedger,
) : Orchestrator {

    // Supervisor scope for fire-and-forget post-turn learning tasks.
    private val learningScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    override fun run(
        conversationId: String,
        userMessage: String,
        recentMessages: List<LlmMessage>,
        origin: ExecutionOrigin,
    ): Flow<OrchestratorEvent> = flow {

        // 1. Route.
        val routing = agentRouter.route(userMessage)
        val primaryRole = when (routing) {
            is RoutingResult.Solo -> routing.agent
            is RoutingResult.MultiAgent -> routing.agents.first()
            is RoutingResult.Fallback -> AgentRole.DEFAULT
        }
        Timber.tag("Orchestrator").d("Routed to %s", primaryRole)

        // 2. Build plan.
        val plan = buildPlan(conversationId, userMessage, routing)
        executionPlanRepository.save(plan)
        emit(OrchestratorEvent.PlanReady(plan))

        // 3. Load memories + user model and inject into system prompt.
        // The four context lookups are independent — run them concurrently so
        // the pre-first-token wait is the slowest one, not the sum of all four.
        val contextStart = System.currentTimeMillis()
        val (memories, ragContext, userModel, skillBlockDeferred) = coroutineScope {
            val memoriesJob = async {
                runCatching { memoryRepository.searchMemories(userMessage, limit = 15) }
                    .getOrDefault(emptyList())
            }
            val ragJob = async {
                runCatching { ragPipeline.buildContext(userMessage, maxChars = 3000) }
                    .getOrDefault("")
            }
            val userModelJob = async {
                runCatching { userModelService.currentModel() }.getOrNull()
            }
            val skillJob = async {
                runCatching { skillMatcher.findRelevantSkill(userMessage) }
                    .getOrNull()
                    ?.let { skillMatcher.renderSkillBlock(it) }
                    ?: ""
            }
            ContextLookups(memoriesJob.await(), ragJob.await(), userModelJob.await(), skillJob.await())
        }
        Timber.tag("Orchestrator").d(
            "context lookups took %d ms", System.currentTimeMillis() - contextStart,
        )

        val memoryBlock = buildString {
            if (userModel != null) {
                append("\n\n## User profile\n$userModel")
            }
            val regularMemories = memories.filter {
                !it.content.startsWith(UserModelService.MODEL_PREFIX)
            }
            if (regularMemories.isNotEmpty()) {
                append("\n\n## What you know about the user\n")
                regularMemories.forEach { m -> append("- ${m.content}\n") }
                append("\nUse this context naturally. ")
                append("Save any new personal facts with the memory tool (action='add').")
            }
            if (ragContext.isNotBlank()) {
                append("\n\n## Relevant Personal Documents\n")
                append(ragContext)
                append("\nUse this context to inform your answers when asked about the user's notes or documents.")
            }
        }

        // 3.5. Skill block was fetched concurrently above (deterministic
        // lexical match — zero LLM cost; see SkillMatcher).
        val skillBlock = skillBlockDeferred

        // 4. Execute each step; collect all tool names used for learning.
        val aggregator = StringBuilder()
        val allToolsUsed = mutableListOf<String>()
        var lastProviderWasOnDevice = true

        for (step in plan.steps) {
            executionPlanRepository.markStepRunning(step.id)
            emit(OrchestratorEvent.StepStarted(step.id, step.agentRole))

            val agent = agentRegistry.get(step.agentRole)
            val tools = agent.availableTools(toolRegistry)

            // Pin a single text tool-call format so models that don't use
            // structured tool_calls (Gemma's ```tool_code```, Nemotron's
            // <TOOLCALL>) emit the <tool_call> JSON the parser recovers.
            val previousContext = if (aggregator.isNotEmpty()) {
                "\n\n## Context from previous agents\n$aggregator"
            } else ""

            val toolInstruction = if (tools.isNotEmpty()) ToolCallPrompt.INSTRUCTION else ""
            val llmMessages = buildList {
                add(LlmMessage(role = "system", content = agent.systemPrompt + memoryBlock + skillBlock + previousContext + toolInstruction))
                addAll(recentMessages)
                if (recentMessages.none { it.role == "user" && it.content == userMessage }) {
                    add(LlmMessage(role = "user", content = userMessage))
                }
            }

            val decision = llmRouter.route(llmMessages)
            val provider = when (decision) {
                is RoutingDecision.Ready -> decision.provider
                is RoutingDecision.Unavailable -> {
                    executionPlanRepository.markStepFinished(
                        step.id,
                        StepStatus.FAILED,
                        decision.reason,
                    )
                    emit(OrchestratorEvent.StepFinished(step.id, success = false))
                    emit(OrchestratorEvent.Failed(decision.reason))
                    return@flow
                }
            }
            val loopOutcome = try {
                agentLoopRunner.run(
                    provider = provider,
                    initialMessages = llmMessages,
                    tools = tools,
                    origin = origin,
                    onToolRequested = { call, requiresConfirmation ->
                        emit(OrchestratorEvent.ToolCallRequested(call, requiresConfirmation))
                    },
                    confirmationGate = ToolCallExecutor.ConfirmationGate { call, requiresConfirmation ->
                        if (requiresConfirmation) toolConfirmationService.awaitConfirmation(call) else true
                    },
                    onToolResult = { call, result ->
                        activityLedger.record(
                            ActivityEntry(
                                timestamp = System.currentTimeMillis(),
                                kind = ActivityKind.TOOL_CALL,
                                origin = origin.name.lowercase(),
                                conversationId = conversationId,
                                title = call.name,
                                detail = (result.output.ifEmpty { result.errorMessage.orEmpty() }).take(500),
                                success = result.success,
                            ),
                        )
                        emit(
                            OrchestratorEvent.ToolCallResult(
                                call = call,
                                output = result.output.ifEmpty { result.errorMessage.orEmpty() },
                                success = result.success,
                            ),
                        )
                    },
                )
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    executionPlanRepository.markStepFinished(
                        step.id,
                        StepStatus.BLOCKED,
                        "Execution was interrupted before this step completed.",
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: "The plan step failed unexpectedly."
                executionPlanRepository.markStepFinished(step.id, StepStatus.FAILED, message)
                emit(OrchestratorEvent.StepFinished(step.id, success = false))
                emit(OrchestratorEvent.Failed(message))
                return@flow
            }

            val completed = when (loopOutcome) {
                is AgentLoopOutcome.Completed -> loopOutcome
                is AgentLoopOutcome.Failed -> {
                    allToolsUsed += loopOutcome.toolsInvoked
                    executionPlanRepository.markStepFinished(
                        step.id,
                        StepStatus.FAILED,
                        loopOutcome.userMessage,
                    )
                    emit(OrchestratorEvent.StepFinished(step.id, success = false))
                    emit(OrchestratorEvent.Failed(loopOutcome.userMessage))
                    return@flow
                }
            }

            lastProviderWasOnDevice = provider.isOnDevice
            allToolsUsed += completed.toolsInvoked
            aggregator.append(completed.reply)
            emit(OrchestratorEvent.ReplyToken(completed.reply))
            executionPlanRepository.markStepFinished(step.id, StepStatus.SUCCEEDED)
            emit(OrchestratorEvent.StepFinished(step.id, success = true))
        }

        val finalText = aggregator.toString()
        emit(
            OrchestratorEvent.ReplyComplete(
                finalText = finalText,
                agentRole = primaryRole,
                isOnDevice = lastProviderWasOnDevice,
            )
        )

        // 5. Fire-and-forget learning tasks — do NOT block the UI.
        learningScope.launch {
            // Extract personal facts from this turn.
            conversationLearner.extractAndLearn(userMessage, finalText)

            // Auto-create a skill if this was a complex multi-tool task.
            if (allToolsUsed.toSet().size >= 2) {
                autonomousSkillCreator.maybeCreateSkill(userMessage, finalText, allToolsUsed)
            }

            // Update the user model every N conversations.
            userModelService.onConversationComplete()
        }
    }
        // Live "thinking" presence: any orchestrator run (chat, kanban,
        // delegate, API server) flips the process-wide activity signal the
        // home screen's eyes observe.
        .onStart { AgentActivity.begin() }
        .onCompletion { AgentActivity.end() }
        .flowOn(dispatchers.io)

    /** Build the deterministic role plan for this conversation turn. */
    private fun buildPlan(
        conversationId: String,
        userMessage: String,
        routing: RoutingResult,
    ): ExecutionPlan {
        val now = System.currentTimeMillis()
        val steps = when (routing) {
            is RoutingResult.Solo -> listOf(
                ExecutionStep(
                    id = IdGenerator.newId(),
                    agentRole = routing.agent,
                    description = "Handle user request: ${userMessage.take(80)}",
                )
            )
            is RoutingResult.MultiAgent -> buildList {
                routing.agents.forEachIndexed { i, role ->
                    val previousStepId = lastOrNull()?.id
                    add(
                        ExecutionStep(
                            id = IdGenerator.newId(),
                            agentRole = role,
                            description = if (i == 0) "Research: ${userMessage.take(80)}"
                            else "Continue using the previous agent's result.",
                            dependsOn = previousStepId?.let(::listOf).orEmpty(),
                        ),
                    )
                }
            }
            is RoutingResult.Fallback -> listOf(
                ExecutionStep(
                    id = IdGenerator.newId(),
                    agentRole = AgentRole.DEFAULT,
                    description = "Fallback: ${userMessage.take(80)}",
                )
            )
        }
        return ExecutionPlan(
            id = IdGenerator.newId(),
            conversationId = conversationId,
            userMessage = userMessage,
            steps = steps,
            createdAt = now,
        )
    }

}

/** Results of the concurrent pre-turn context lookups. */
private data class ContextLookups(
    val memories: List<com.hermes.agent.domain.model.Memory>,
    val ragContext: String,
    val userModel: String?,
    val skillBlock: String,
)
