package com.hermes.agent.data.agent

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.ToolCall
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
    USER_DECLINED,
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
    ): AgentLoopOutcome {
        // Collected outside the timeout. When the budget ran out this reported
        // no tools at all, so a turn that had already created a task looked to
        // every caller like nothing had happened.
        val toolsInvoked = mutableListOf<String>()
        return withTimeoutOrNull(MAX_LOOP_DURATION_MS) {
            runWithinBudget(
                provider,
                initialMessages,
                tools,
                origin,
                onToolRequested,
                confirmationGate,
                onToolResult,
                toolsInvoked,
            )
        } ?: AgentLoopOutcome.Failed(
            AgentLoopFailureReason.TIMED_OUT,
            "Hermes stopped because this task took too long. Try again or split it into smaller steps.",
            toolsInvoked.toList(),
        )
    }

    private suspend fun runWithinBudget(
        provider: LlmProvider,
        initialMessages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
        origin: ExecutionOrigin,
        onToolRequested: suspend (ToolCall, Boolean) -> Unit,
        confirmationGate: ToolCallExecutor.ConfirmationGate?,
        onToolResult: suspend (ToolCall, ToolResult) -> Unit,
        toolsInvoked: MutableList<String>,
    ): AgentLoopOutcome {
        var messages = initialMessages
        val guardSession = executionGuard.openSession()
        // Results of calls recovered from a model that could not produce the
        // tool envelope. Only those are answered from here on a repeat: such a
        // model routinely cannot read a tool result and reissues the identical
        // call, which for a create-style tool wrote a duplicate row every round.
        // A call the model formatted properly is left alone, because repeating
        // one with a changing result is legitimate progress.
        val recoveredResults = mutableMapOf<String, ToolResult>()

        repeat(MAX_TOOL_ROUNDS) { round ->
            val response = provider.completeWithTools(messages, tools)
            if (response.toolCalls.isEmpty()) {
                return AgentLoopOutcome.Completed(response.content, toolsInvoked.toList())
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

                val signature = call.name + "|" +
                    RepeatedExecutionGuard.canonicalArguments(call.arguments)
                val repeated = if (call.id.startsWith(RECOVERED_CALL_PREFIX)) {
                    recoveredResults[signature]
                } else {
                    null
                }
                // Asked once, in the same order as before: never prompt for a tool that
                // is unauthorised or already denied by policy. A null answer means there
                // was no gate to ask (a headless turn) - not the same as a person saying
                // no, and it keeps its original behaviour in the branch below.
                val confirmed: Boolean? =
                    if (mustConfirm &&
                        tools.any { it.name == call.name } &&
                        decision !is ToolExecutionDecision.Deny
                    ) {
                        confirmationGate?.confirm(call, true)
                    } else {
                        null
                    }

                if (confirmed == false) {
                    // Handing a refusal back as one more tool observation let the loop
                    // continue, and the model narrated a success that never happened —
                    // it answered "Here are all the entities currently configured in
                    // your Home Assistant instance" for a call the user had just
                    // refused. A refusal is the user's decision, not a data point to
                    // reason around: end the step and say so in words the model cannot
                    // overwrite. Work already completed is still reported, because the
                    // orchestrator folds completedWork into the failure message.
                    val declineResult = ToolResult.error("user declined to run '${call.name}'")
                    onToolResult(call, declineResult)
                    return AgentLoopOutcome.Failed(
                        AgentLoopFailureReason.USER_DECLINED,
                        "I did not run ${call.name} because you declined it. " +
                            "Nothing was changed and no data was read.",
                        toolsInvoked.toList(),
                    )
                }

                val result = when {
                    tools.none { it.name == call.name } -> ToolResult.error("unauthorized tool: ${call.name}")
                    decision is ToolExecutionDecision.Deny ->
                        // Spelled out so a small model cannot read a bare reason string
                        // as a result it may summarise. Same failure mode as K41.
                        ToolResult.error(
                            "REFUSED: ${decision.reason}. This tool did not run and " +
                                "returned no data. Tell the user it was refused; do not " +
                                "describe results you did not receive."
                        )
                    // Headless: there was no gate to ask, so a confirmation-required
                    // tool still cannot run. Unchanged from before this fix.
                    mustConfirm && confirmed != true -> ToolResult.error("user declined")
                    // A small model that cannot read a tool result often just
                    // reissues the same call. Executing it again created a
                    // second identical task rather than answering the user.
                    repeated != null -> repeated
                    else -> toolCallExecutor.execute(call, confirmationGate = null).also {
                        if (call.id.startsWith(RECOVERED_CALL_PREFIX)) recoveredResults[signature] = it
                    }
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
                    "Hermes stopped because the same tool actions repeated without making progress. Try rephrasing the request or changing the inputs.",
                    toolsInvoked.toList(),
                )
            }
        }

        return AgentLoopOutcome.Failed(
            AgentLoopFailureReason.ROUND_LIMIT_REACHED,
            "Hermes reached the tool-step limit before finishing. Try splitting the request into smaller steps.",
            toolsInvoked.toList(),
        )
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 12

        /** Marks a call rebuilt from a reply that did not use the tool envelope. */
        const val RECOVERED_CALL_PREFIX = "recovered_call_"
        // Covers a reasoning model's deepest thinking floor (10 min in
        // ReasoningStaleTimeout) plus a couple of fast tool rounds.
        const val MAX_LOOP_DURATION_MS = 12 * 60 * 1000L
    }
}
