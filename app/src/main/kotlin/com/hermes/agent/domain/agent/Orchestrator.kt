package com.hermes.agent.domain.agent

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmStreamChunk
import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.domain.model.AgentRun
import com.hermes.agent.domain.model.ExecutionPlan
import kotlinx.coroutines.flow.Flow

/**
 * Event stream emitted by [Orchestrator.run]. The chat ViewModel
 * consumes this as a single Flow and updates its UI state per event.
 *
 * Phase 2 events cover the full plan-then-execute lifecycle:
 *   1. [PlanReady]  — the orchestrator has produced an ExecutionPlan.
 *   2. [StepStarted] / [StepFinished] — per-step transitions.
 *   3. [ToolCallRequested] — the LLM wants to invoke a tool. The UI may
 *      show a confirmation dialog if the tool requires it.
 *   4. [ToolCallResult] — the tool returned; results fed back to the LLM.
 *   5. [ReplyToken] — incremental token of the final reply.
 *   6. [ReplyComplete] — final reply is done; carries the persisted
 *      assistant message id.
 *   7. [Failed] — unrecoverable error.
 */
sealed class OrchestratorEvent {
    data class PlanReady(val plan: ExecutionPlan) : OrchestratorEvent()
    data class StepStarted(val stepId: String, val agentRole: com.hermes.agent.domain.model.AgentRole) : OrchestratorEvent()
    data class ToolCallRequested(val call: ToolCall, val requiresConfirmation: Boolean) : OrchestratorEvent()
    data class ToolCallResult(val call: ToolCall, val output: String, val success: Boolean) : OrchestratorEvent()
    data class StepFinished(val stepId: String, val success: Boolean) : OrchestratorEvent()
    data class ReplyToken(val text: String) : OrchestratorEvent()
    data class ReplyComplete(val finalText: String, val agentRole: com.hermes.agent.domain.model.AgentRole, val isOnDevice: Boolean) : OrchestratorEvent()
    data class Failed(val message: String) : OrchestratorEvent()
    data class StateChanged(val run: AgentRun) : OrchestratorEvent()
}

/**
 * Top-level orchestrator that coordinates a single user-message round.
 *
 * Implements the plan-then-execute pattern from Section 6.1 of the plan.
 *
 * Lifecycle per call:
 *   1. Classify intent ([AgentRouter]).
 *   2. Generate an [ExecutionPlan] (single-step in Phase 2; multi-step
 *      planning staged for Phase 3).
 *   3. Emit [OrchestratorEvent.PlanReady].
 *   4. For each step:
 *      a. Build the LLM prompt with the agent's system prompt + recent
 *         conversation context.
 *      b. Call the LLM with tools.
 *      c. If the LLM emits tool calls: execute each via
 *         [com.hermes.agent.data.tool.ToolCallExecutor], feed results
 *         back, re-prompt. Repeat up to `maxToolRounds` (default 3).
 *      d. Emit [OrchestratorEvent.ReplyToken] events as the final
 *         reply streams in.
 *   5. Emit [OrchestratorEvent.ReplyComplete].
 */
interface Orchestrator {
    fun run(
        conversationId: String,
        userMessage: String,
        recentMessages: List<LlmMessage>,
        origin: ExecutionOrigin,
    ): Flow<OrchestratorEvent>
}
