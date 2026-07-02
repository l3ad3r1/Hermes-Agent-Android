package com.hermes.agent.data.tool

import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.data.security.OutputRedactor
import com.hermes.agent.domain.tool.ToolResult
import com.hermes.agent.domain.tool.ToolRegistry
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes [ToolCall]s emitted by an LLM through the [ToolRegistry].
 *
 * Kept as a separate collaborator (rather than inlined into the
 * orchestrator) so the execution path can be unit-tested in isolation
 * and so the orchestrator's main loop stays readable.
 *
 * @property requiresConfirmationGate Called for every tool whose
 *   descriptor declares `requiresConfirmation = true`. The orchestrator
 *   supplies a gate that suspends until the user accepts or rejects the
 *   invocation. Default implementation auto-approves — the orchestrator
 *   overrides this with a real UI-driven gate.
 */
@Singleton
class ToolCallExecutor @Inject constructor(
    private val registry: ToolRegistry,
    private val redactor: OutputRedactor,
) {

    /**
     * Functional type of the confirmation gate. Receives the tool call
     * and the descriptor; returns true to proceed, false to skip.
     */
    fun interface ConfirmationGate {
        suspend fun confirm(call: ToolCall, requiresConfirmation: Boolean): Boolean
    }

    /**
     * Run a single tool call to completion.
     *
     * Returns a [ToolResult] — always. Errors are caught and surfaced
     * as `ToolResult.error` rather than thrown, so the orchestrator's
     * main loop can keep going.
     */
    suspend fun execute(
        call: ToolCall,
        confirmationGate: ConfirmationGate? = null,
    ): ToolResult {
        val tool = registry.byName(call.name)
            ?: return ToolResult.error("unknown tool: ${call.name}")

        if (tool.descriptor.requiresConfirmation) {
            val approved = confirmationGate?.confirm(call, requiresConfirmation = true) ?: true
            if (!approved) {
                return ToolResult.error("user declined to run tool '${call.name}'")
            }
        }

        val start = System.currentTimeMillis()
        val result = runCatching { tool.execute(call.arguments) }
            .mapCatching { result ->
                result.copy(executionMs = result.executionMs + (System.currentTimeMillis() - start))
            }
            .getOrElse { t ->
                Timber.tag("ToolExec").w(t, "tool '%s' threw", call.name)
                ToolResult.error(
                    message = t.message ?: "tool '${call.name}' threw ${t::class.simpleName}",
                    executionMs = System.currentTimeMillis() - start,
                )
            }
        // Redact secrets from anything the tool returns before it re-enters
        // the conversation (and from there the UI, history, or `notify`).
        return runCatching {
            result.copy(
                output = redactor.redact(result.output),
                errorMessage = result.errorMessage?.let { redactor.redact(it) },
            )
        }.getOrDefault(result)
    }

    /**
     * Run a list of tool calls in order. Failures don't abort the batch —
     * each call's [ToolResult] is reported independently.
     */
    suspend fun executeAll(
        calls: List<ToolCall>,
        confirmationGate: ConfirmationGate? = null,
    ): List<Pair<ToolCall, ToolResult>> = calls.map { call ->
        call to execute(call, confirmationGate)
    }
}
