package com.hermes.agent.data.remote

/**
 * Parsed SSE events from the PC Hermes gateway's `GET /v1/runs/{id}/events`
 * stream. Each event type maps to a lifecycle stage of a remote agent run.
 *
 * The gateway emits structured lifecycle events (not OpenAI text-delta chunks):
 * `message.delta`, `message.complete`, `tool.started`, `tool.completed`,
 * `approval.request`, `run.completed`, `run.failed`, `subagent.start`,
 * `subagent.complete`, and others. [GatewayApiClient] parses the raw SSE
 * `data:` lines into these sealed subclasses.
 *
 * @see com.hermes.agent.data.remote.RemoteOrchestrator for how each event
 *   maps to [com.hermes.agent.domain.agent.OrchestratorEvent].
 */
sealed class GatewayEvent {
    /** Incremental text chunk of the assistant reply. */
    data class MessageDelta(val text: String) : GatewayEvent()

    /** Final complete assistant reply text. Terminal for the reply phase. */
    data class MessageComplete(val text: String) : GatewayEvent()

    /** A tool call has started executing on the PC. */
    data class ToolStarted(
        val callId: String,
        val name: String,
        val arguments: String,
    ) : GatewayEvent()

    /** A tool call has finished executing on the PC. */
    data class ToolCompleted(
        val callId: String,
        val name: String,
        val output: String,
        val success: Boolean,
    ) : GatewayEvent()

    /**
     * The PC agent is waiting for human approval before proceeding with a
     * tool call. The phone must surface an approval dialog and call
     * `POST /v1/runs/{id}/approval` with the user's decision.
     */
    data class ApprovalRequested(
        val callId: String,
        val toolName: String,
        val arguments: String,
        /**
         * Gateway-side id of the pending approval. Room-scoped or
         * profile-scoped gateway instances require it to be echoed back
         * on the approval POST and reject requests without it.
         */
        val requestId: String,
    ) : GatewayEvent()

    /** The run has completed successfully. Terminal for the entire run. */
    data class RunCompleted(val output: String) : GatewayEvent()

    /** The run has failed. Terminal for the entire run. */
    data class RunFailed(val message: String) : GatewayEvent()

    /** A background subagent has started. Informational. */
    data class SubagentStarted(
        val childSessionId: String,
        val delegationId: String,
    ) : GatewayEvent()

    /** A background subagent has finished. Informational. */
    data class SubagentCompleted(
        val childSessionId: String,
        val status: String,
        val summary: String,
    ) : GatewayEvent()

    /** An unrecognized event — logged but not surfaced to the UI. */
    data class Unknown(val type: String, val raw: String) : GatewayEvent()
}
