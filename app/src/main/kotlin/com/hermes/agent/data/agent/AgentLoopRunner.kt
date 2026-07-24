package com.hermes.agent.data.agent

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmProvider
import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.data.tool.ToolCallExecutor
import com.hermes.agent.domain.agent.ExecutionGuard
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.ExecutionStopReason
import com.hermes.agent.domain.agent.ToolExecutionObservation
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolExecutionDecision
import com.hermes.agent.domain.tool.ToolExecutionPolicy
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class AgentLoopFailureReason {
    REPEATED_NO_PROGRESS,
    ROUND_LIMIT_REACHED,
    TIMED_OUT,
}

sealed interface AgentLoopOutcome {
    val toolsInvoked: List<String>

    data class Completed(
        val reply: String,
        override val toolsInvoked: List<String>,
    ) : AgentLoopOutcome

    data class Failed(
        val reason: AgentLoopFailureReason,
        val userMessage: String,
        override val toolsInvoked: List<String>,
    ) : AgentLoopOutcome
}

/** Owns one bounded LLM/tool exchange and delegates progress policy to [ExecutionGuard]. */
@Singleton
class AgentLoopRunner @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val toolCallExecutor: ToolCallExecutor,
    private val executionGuard: ExecutionGuard,
    private val executionPolicy: ToolExecutionPolicy,
) {
    suspend fun run(
        provider: LlmProvider,
        initialMessages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
        origin: ExecutionOrigin,
        onToolRequested: suspend (ToolCall, Boolean) -> Unit,
        confirmationGate: ToolCallExecutor.ConfirmationGate?,
        onToolResult: suspend (ToolCall, ToolResult) -> Unit,
    ): AgentLoopOutcome = withTimeoutOrNull(MAX_LOOP_DURATION_MS) {
        runWithinBudget(
            provider,
            initialMessages,
            tools,
            origin,
            onToolRequested,
            confirmationGate,
            onToolResult,
        )
    } ?: AgentLoopOutcome.Failed(
        AgentLoopFailureReason.TIMED_OUT,
        "Jeeves stopped because this task took too long. Try again or split it into smaller steps.",
        emptyList(),
    )

    private suspend fun runWithinBudget(
        provider: LlmProvider,
        initialMessages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
        origin: ExecutionOrigin,
        onToolRequested: suspend (ToolCall, Boolean) -> Unit,
        confirmationGate: ToolCallExecutor.ConfirmationGate?,
        onToolResult: suspend (ToolCall, ToolResult) -> Unit,
    ): AgentLoopOutcome {
        var messages = initialMessages
        val toolsInvoked = mutableListOf<String>()
        val guardSession = executionGuard.openSession()

        repeat(MAX_TOOL_ROUNDS) { round ->
            val response = provider.completeWithTools(messages, tools)
            if (response.toolCalls.isEmpty()) {
                return AgentLoopOutcome.Completed(response.content, toolsInvoked)
            }

            messages = messages + LlmMessage(
                role = "assistant",
                content = response.content,
                toolCalls = response.toolCalls,
            )

            val observations = mutableListOf<ToolExecutionObservation>()
            for (call in response.toolCalls) {
                toolsInvoked += call.name
                val requiresConfirmation =
                    toolRegistry.byName(call.name)?.descriptor?.requiresConfirmation ?: false
                val decision = executionPolicy.evaluate(origin, call.name, requiresConfirmation)
                val mustConfirm = decision is ToolExecutionDecision.Confirm
                onToolRequested(call, mustConfirm)

                val result = when {
                    tools.none { it.name == call.name } -> ToolResult.error("unauthorized tool: ${call.name}")
                    decision is ToolExecutionDecision.Deny -> ToolResult.error(decision.reason)
                    mustConfirm && confirmationGate?.confirm(call, true) != true ->
                        ToolResult.error("user declined")
                    else -> toolCallExecutor.execute(call, confirmationGate = null)
                }

                onToolResult(call, result)
                observations += ToolExecutionObservation(call, result)
                messages = messages + LlmMessage(
                    role = "tool",
                    content = result.output.ifEmpty { result.errorMessage ?: "(no output)" },
                    toolCallId = call.id,
                )
            }

            Timber.tag("AgentLoop").d("tool loop round %d, %d calls", round, response.toolCalls.size)
            if (guardSession.observeRound(observations) == ExecutionStopReason.REPEATED_NO_PROGRESS) {
                return AgentLoopOutcome.Failed(
                    AgentLoopFailureReason.REPEATED_NO_PROGRESS,
                    "Jeeves stopped because the same tool actions repeated without making progress. Try rephrasing the request or changing the inputs.",
                    toolsInvoked,
                )
            }
        }

        return AgentLoopOutcome.Failed(
            AgentLoopFailureReason.ROUND_LIMIT_REACHED,
            "Jeeves reached the tool-step limit before finishing. Try splitting the request into smaller steps.",
            toolsInvoked,
        )
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 5
        const val MAX_LOOP_DURATION_MS = 5 * 60 * 1000L
    }
}
