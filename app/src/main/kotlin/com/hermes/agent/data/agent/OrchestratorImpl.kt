package com.hermes.agent.data.agent

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmProvider
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.LlmStreamChunk
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.data.memory.ConversationLearner
import com.hermes.agent.data.memory.UserModelService
import com.hermes.agent.data.tool.ToolCallExecutor
import com.hermes.agent.domain.agent.AgentRouter
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.agent.RoutingResult
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.ExecutionPlan
import com.hermes.agent.domain.model.ExecutionStep
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.util.DispatcherProvider
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
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
    private val toolCallExecutor: ToolCallExecutor,
    private val dispatchers: DispatcherProvider,
    private val memoryRepository: MemoryRepository,
    private val conversationLearner: ConversationLearner,
    private val autonomousSkillCreator: AutonomousSkillCreator,
    private val userModelService: UserModelService,
    private val skillMatcher: SkillMatcher,
) : Orchestrator {

    // Supervisor scope for fire-and-forget post-turn learning tasks.
    private val learningScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    override fun run(
        conversationId: String,
        userMessage: String,
        recentMessages: List<LlmMessage>,
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
        emit(OrchestratorEvent.PlanReady(plan))

        // 3. Load memories + user model and inject into system prompt.
        val memories = runCatching { memoryRepository.searchMemories("", limit = 30) }
            .getOrDefault(emptyList())

        val userModel = runCatching { userModelService.currentModel() }.getOrNull()

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
        }

        // 3.5. Skill orchestrator: check whether an existing skill makes
        // this request more efficient; if so, inject it for this turn.
        // Deterministic lexical match — zero LLM cost (see SkillMatcher).
        val skillBlock = runCatching { skillMatcher.findRelevantSkill(userMessage) }
            .getOrNull()
            ?.let { skillMatcher.renderSkillBlock(it) }
            ?: ""

        // 4. Execute each step; collect all tool names used for learning.
        val aggregator = StringBuilder()
        val allToolsUsed = mutableListOf<String>()
        var lastProviderWasOnDevice = true

        for (step in plan.steps) {
            emit(OrchestratorEvent.StepStarted(step.id, step.agentRole))

            val agent = agentRegistry.get(step.agentRole)
            val tools = agent.availableTools(toolRegistry)

            // Pin a single text tool-call format so models that don't use
            // structured tool_calls (Gemma's ```tool_code```, Nemotron's
            // <TOOLCALL>) emit the <tool_call> JSON the parser recovers.
            val toolInstruction = if (tools.isNotEmpty()) ToolCallPrompt.INSTRUCTION else ""
            val llmMessages = buildList {
                add(LlmMessage(role = "system", content = agent.systemPrompt + memoryBlock + skillBlock + toolInstruction))
                addAll(recentMessages)
                if (recentMessages.none { it.role == "user" && it.content == userMessage }) {
                    add(LlmMessage(role = "user", content = userMessage))
                }
            }

            val decision = llmRouter.route(llmMessages)
            val provider = when (decision) {
                is RoutingDecision.Cloud -> decision.provider
                is RoutingDecision.Unavailable -> {
                    emit(OrchestratorEvent.Failed(decision.reason))
                    return@flow
                }
            }
            lastProviderWasOnDevice = provider.isOnDevice

            val (finalReply, stepTools) = runToolLoop(provider, llmMessages, tools) { call, requiresConfirmation ->
                emit(OrchestratorEvent.ToolCallRequested(call, requiresConfirmation))
                true
            }

            if (finalReply == null) {
                emit(OrchestratorEvent.Failed("tool loop exhausted without final reply"))
                return@flow
            }

            allToolsUsed += stepTools
            aggregator.append(finalReply)
            emit(OrchestratorEvent.ReplyToken(finalReply))
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
        .flowOn(dispatchers.io)

    /**
     * Run the LLM ↔ tool-call loop until the LLM emits a content reply
     * or [MAX_TOOL_ROUNDS] is exceeded.
     *
     * Returns Pair(finalReply or null, list of tool names invoked).
     */
    private suspend fun runToolLoop(
        provider: LlmProvider,
        initialMessages: List<LlmMessage>,
        tools: List<com.hermes.agent.domain.tool.ToolDescriptor>,
        confirmationGate: ToolCallExecutor.ConfirmationGate?,
    ): Pair<String?, List<String>> {
        var messages = initialMessages
        val toolsInvoked = mutableListOf<String>()

        repeat(MAX_TOOL_ROUNDS) { round ->
            val response = provider.completeWithTools(messages, tools)
            if (response.toolCalls.isEmpty()) {
                return Pair(response.content, toolsInvoked)
            }

            messages = messages.toMutableList().apply {
                add(
                    LlmMessage(
                        role = "assistant",
                        content = response.content,
                        toolCalls = response.toolCalls,
                    )
                )
            }

            for (call in response.toolCalls) {
                toolsInvoked += call.name
                val requiresConfirmation =
                    toolRegistry.byName(call.name)?.descriptor?.requiresConfirmation ?: false
                val approved = confirmationGate?.confirm(call, requiresConfirmation) ?: true
                val result = if (approved) {
                    toolCallExecutor.execute(call, confirmationGate = null)
                } else {
                    com.hermes.agent.domain.tool.ToolResult.error("user declined")
                }
                messages = messages.toMutableList().apply {
                    add(
                        LlmMessage(
                            role = "tool",
                            content = result.output.ifEmpty { result.errorMessage ?: "(no output)" },
                            toolCallId = call.id,
                        )
                    )
                }
            }
            Timber.tag("Orchestrator").d("tool loop round %d, %d calls", round, response.toolCalls.size)
        }
        return Pair(null, toolsInvoked)
    }

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
            is RoutingResult.MultiAgent -> routing.agents.mapIndexed { i, role ->
                ExecutionStep(
                    id = IdGenerator.newId(),
                    agentRole = role,
                    description = if (i == 0) "Research: ${userMessage.take(80)}"
                    else "Creative: draft based on research.",
                    dependsOn = if (i == 0) emptyList() else listOf("step-0"),
                )
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

    companion object {
        const val MAX_TOOL_ROUNDS = 5
    }
}
