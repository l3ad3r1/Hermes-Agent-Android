package com.hermes.agent.data.agent

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmProvider
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.LlmStreamChunk
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.data.tool.ToolCallExecutor
import com.hermes.agent.domain.agent.AgentRouter
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.agent.RoutingResult
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.ExecutionPlan
import com.hermes.agent.domain.model.ExecutionStep
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.util.DispatcherProvider
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [Orchestrator] implementation.
 *
 * Wires together [AgentRouter] (intent classification), [AgentRegistry]
 * (persona lookup), [ToolRegistry] (capability advertisement), [LlmRouter]
 * (provider selection), and [ToolCallExecutor] (function-call execution).
 *
 * Streaming flow (cold Flow, collected by the chat ViewModel):
 *
 *   1. Route user message → RoutingResult.
 *   2. Build ExecutionPlan with one or more ExecutionSteps.
 *   3. Emit [OrchestratorEvent.PlanReady].
 *   4. For each step:
 *      a. Resolve the agent.
 *      b. Build LlmMessage list: agent's system prompt + recent context.
 *      c. Route to LlmProvider.
 *      d. Call provider.completeWithTools(messages, agent.availableTools).
 *      e. If response.toolCalls is non-empty:
 *         - For each call, emit ToolCallRequested.
 *         - Execute via ToolCallExecutor (with confirmation gate).
 *         - Emit ToolCallResult.
 *         - Append a tool-role message to the LLM context.
 *         - Re-call the provider. Repeat up to MAX_TOOL_ROUNDS.
 *      f. Once the provider returns content (no tool calls), stream it
 *         token-by-token via provider.stream and emit ReplyToken events.
 *      g. Emit StepFinished.
 *   5. Emit ReplyComplete with the final aggregated text.
 *
 * Errors at any step emit [OrchestratorEvent.Failed] and terminate.
 */
@Singleton
class OrchestratorImpl @Inject constructor(
    private val agentRouter: AgentRouter,
    private val agentRegistry: AgentRegistry,
    private val toolRegistry: ToolRegistry,
    private val llmRouter: LlmRouter,
    private val toolCallExecutor: ToolCallExecutor,
    private val dispatchers: DispatcherProvider,
) : Orchestrator {

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

        // 3. Execute each step.
        val aggregator = StringBuilder()
        var lastProviderWasOnDevice = true
        for (step in plan.steps) {
            emit(OrchestratorEvent.StepStarted(step.id, step.agentRole))

            val agent = agentRegistry.get(step.agentRole)
            val tools = agent.availableTools(toolRegistry)

            val llmMessages = buildList {
                add(LlmMessage(role = "system", content = agent.systemPrompt))
                addAll(recentMessages)
                // The current user message may already be in recentMessages
                // (the chat repository persists it before invoking the
                // orchestrator). If not, append it.
                if (recentMessages.none { it.role == "user" && it.content == userMessage }) {
                    add(LlmMessage(role = "user", content = userMessage))
                }
            }

            // 4. Route to LlmProvider and run the tool-call loop.
            val decision = llmRouter.route(llmMessages)
            val provider = when (decision) {
                is RoutingDecision.OnDevice -> decision.provider
                is RoutingDecision.Cloud -> decision.provider
                is RoutingDecision.Unavailable -> {
                    emit(OrchestratorEvent.Failed(decision.reason))
                    return@flow
                }
            }
            lastProviderWasOnDevice = provider.isOnDevice

            val finalReply = runToolLoop(provider, llmMessages, tools) { call, requiresConfirmation ->
                emit(OrchestratorEvent.ToolCallRequested(call, requiresConfirmation))
                // Phase 2: auto-approve. Phase 3 will suspend on a UI-driven gate.
                true
            } ?: run {
                emit(OrchestratorEvent.Failed("tool loop exhausted without final reply"))
                return@flow
            }

            // 5. Surface the final reply produced by the tool loop. We do NOT
            //    re-prompt the model with its own answer (the old code did, which
            //    doubled the API call and regenerated divergent text).
            aggregator.append(finalReply)
            emit(OrchestratorEvent.ReplyToken(finalReply))

            emit(OrchestratorEvent.StepFinished(step.id, success = true))
        }

        emit(
            OrchestratorEvent.ReplyComplete(
                finalText = aggregator.toString(),
                agentRole = primaryRole,
                isOnDevice = lastProviderWasOnDevice,
            )
        )
    }
        .flowOn(dispatchers.io)

    /**
     * Run the LLM ↔ tool-call loop until the LLM emits a content reply
     * (no tool calls) or [MAX_TOOL_ROUNDS] is exceeded.
     *
     * Returns the final text reply, or null if the loop exhausted without
     * a content reply.
     */
    private suspend fun runToolLoop(
        provider: LlmProvider,
        initialMessages: List<LlmMessage>,
        tools: List<com.hermes.agent.domain.tool.ToolDescriptor>,
        confirmationGate: ToolCallExecutor.ConfirmationGate?,
    ): String? {
        var messages = initialMessages
        repeat(MAX_TOOL_ROUNDS) { round ->
            val response = provider.completeWithTools(messages, tools)
            if (response.toolCalls.isEmpty()) {
                return response.content
            }
            // Execute each tool call and append the results.
            messages = messages.toMutableList().apply {
                // The LLM's tool-call turn (so the next round has context).
                add(
                    LlmMessage(
                        role = "assistant",
                        content = response.content,
                        toolCalls = response.toolCalls,
                    )
                )
            }
            for (call in response.toolCalls) {
                val requiresConfirmation = toolRegistry.byName(call.name)?.descriptor?.requiresConfirmation
                    ?: false
                val approved = confirmationGate?.confirm(call, requiresConfirmation) ?: true
                val result = if (approved) {
                    toolCallExecutor.execute(call, confirmationGate = null)
                } else {
                    com.hermes.agent.domain.tool.ToolResult.error("user declined")
                }
                // We can't emit from here (we're not in the flow), so we
                // emit through the outer flow by raising a sentinel — but
                // the cleaner design is to move the tool-call execution
                // into the outer flow. Phase 2 keeps this structure for
                // readability; the events are reconstructed from the
                // toolCallResults map in [AgentRun] for the UI.
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
            Timber.tag("Orchestrator").d("tool loop round %d complete, %d calls", round, response.toolCalls.size)
        }
        return null
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
                    description = if (i == 0) {
                        "Research phase: ${userMessage.take(80)}"
                    } else {
                        "Creative phase: draft based on research."
                    },
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
        /** Maximum LLM round-trips per orchestrator run (prevents infinite tool-call loops). */
        const val MAX_TOOL_ROUNDS = 3
    }
}
