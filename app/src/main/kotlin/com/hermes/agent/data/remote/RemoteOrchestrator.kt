package com.hermes.agent.data.remote

import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.ToolConfirmationService
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin-client [Orchestrator] that delegates all agent execution to a PC
 * Hermes gateway over the Runs API.
 *
 * The PC is the canonical agent runtime and conversation store. This class:
 *   1. Starts a remote run via [GatewayApiClient.startRun], passing the
 *      conversation ID as `session_id` so the gateway loads the session's
 *      transcript (seamless handoff).
 *   2. Streams lifecycle events from `GET /v1/runs/{id}/events`.
 *   3. Maps each [GatewayEvent] to an [OrchestratorEvent] the existing chat
 *      UI already renders.
 *   4. When the gateway emits an `approval.request`, surfaces the existing
 *      phone-side approval dialog via [ToolConfirmationService] — the same
 *      mechanism [OrchestratorImpl] uses — and forwards the user's decision
 *      back to the PC via `POST /v1/runs/{id}/approval`.
 *
 * No local agent execution, no local LLM call, no local tool execution. The
 * phone is a display + approval surface; the PC does the work.
 *
 * Authority boundary: the run inherits the full authority of the PC gateway
 * (terminal, file ops, etc.). The phone cannot self-limit this — `tool.started`
 * events mean the tool already ran on the PC. Only `approval.request` events
 * actually wait for the phone's decision. To scope the phone's authority,
 * configure a dedicated gateway profile (e.g. `/p/phone/`) with a restricted
 * toolset and point [GatewayApiClient] at that profile prefix. The gateway
 * is the enforcement point.
 */
@Singleton
class RemoteOrchestrator @Inject constructor(
    private val gatewayClient: GatewayApiClient,
    private val toolConfirmationService: ToolConfirmationService,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) : Orchestrator {

    /**
     * Fire-and-forget scope for stop requests: the request must still go out
     * even though the chat job that triggers it is being cancelled.
     */
    private val stopScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** Run currently streaming to this phone, if any (target of [stopActiveRun]). */
    @Volatile
    private var activeRunId: String? = null

    override fun run(
        conversationId: String,
        userMessage: String,
        recentMessages: List<LlmMessage>,
        origin: ExecutionOrigin,
    ): Flow<OrchestratorEvent> = flow {
        // recentMessages is intentionally ignored: the PC gateway loads the
        // session transcript from the session_id we pass to startRun.
        try {
            val runId = gatewayClient.startRun(userMessage, sessionId = conversationId)
            activeRunId = runId
            Timber.tag("RemoteOrchestrator").i("Started remote run=%s session=%s", runId, conversationId)

            // Track whether we've streamed any assistant text via message.delta.
            // The run.completed event's "output" field can contain raw tool
            // output (e.g. ls listing) rather than the assistant's reply, so
            // we only use it as a fallback if no deltas arrived.
            var sawReply = false
            // MessageComplete already terminated the reply; run.completed must
            // not emit a second ReplyComplete (voice mode would speak twice).
            var replyCompleteEmitted = false
            val replyBuilder = StringBuilder()

            gatewayClient.streamRunEvents(runId).onCompletion {
                if (activeRunId == runId) activeRunId = null
            }.collect { event ->
                when (event) {
                    is GatewayEvent.MessageDelta -> {
                        sawReply = true
                        replyBuilder.append(event.text)
                        emit(OrchestratorEvent.ReplyToken(event.text))
                    }

                    is GatewayEvent.MessageComplete -> {
                        sawReply = true
                        replyCompleteEmitted = true
                        emit(OrchestratorEvent.ReplyComplete(
                            event.text,
                            AgentRole.DEFAULT,
                            isOnDevice = false,
                        ))
                    }

                    is GatewayEvent.ToolStarted -> {
                        // Suppress — the phone is a thin client. Tool
                        // execution details live on the PC; the user only
                        // wants the reply text and approval requests.
                    }

                    is GatewayEvent.ToolCompleted -> {
                        // Suppress — see ToolStarted above.
                    }

                    is GatewayEvent.ApprovalRequested -> {
                        // Surface the phone-side approval dialog via the existing
                        // ToolConfirmationService — the same mechanism
                        // OrchestratorImpl uses. The service suspends on a
                        // CompletableDeferred; the UI observes pendingRequest
                        // and calls submitConfirmation() when the user taps.
                        val call = parseToolCall(event.callId, event.toolName, event.arguments)
                        emit(OrchestratorEvent.ToolCallRequested(call, requiresConfirmation = true))
                        val approved = toolConfirmationService.awaitConfirmation(call)
                        // Resolve the tool badge immediately: tool.completed is
                        // deliberately suppressed in thin-client mode, so the
                        // card would otherwise stay stuck in RUNNING until the
                        // entire run finishes.
                        emit(
                            OrchestratorEvent.ToolCallResult(
                                call,
                                if (approved) "Approved" else "Denied by user",
                                approved,
                            ),
                        )
                        Timber.tag("RemoteOrchestrator").i(
                            "Approval call=%s approved=%s — forwarding to PC",
                            event.toolName, approved,
                        )
                        gatewayClient.submitApproval(runId, approved, event.requestId)
                    }

                    is GatewayEvent.RunCompleted -> {
                        // If MessageComplete already emitted the terminal
                        // ReplyComplete, skip all fallback paths — emitting
                        // a second one would double-speak the reply in voice
                        // mode and emit duplicate completion events upstream.
                        if (!replyCompleteEmitted && !sawReply && event.output.isNotBlank()) {
                            emit(OrchestratorEvent.ReplyComplete(
                                event.output,
                                AgentRole.DEFAULT,
                                isOnDevice = false,
                            ))
                        } else if (!replyCompleteEmitted && sawReply && replyBuilder.isNotEmpty()) {
                            // Ensure the UI gets a terminal ReplyComplete from
                            // the accumulated deltas if no MessageComplete fired.
                            emit(OrchestratorEvent.ReplyComplete(
                                replyBuilder.toString(),
                                AgentRole.DEFAULT,
                                isOnDevice = false,
                            ))
                        }
                    }

                    is GatewayEvent.RunFailed -> {
                        emit(OrchestratorEvent.Failed(event.message))
                    }

                    is GatewayEvent.SubagentStarted -> {
                        Timber.tag("RemoteOrchestrator").d(
                            "Subagent started: child=%s delegation=%s",
                            event.childSessionId, event.delegationId,
                        )
                    }

                    is GatewayEvent.SubagentCompleted -> {
                        Timber.tag("RemoteOrchestrator").d(
                            "Subagent completed: child=%s status=%s",
                            event.childSessionId, event.status,
                        )
                    }

                    is GatewayEvent.Unknown -> {
                        Timber.tag("RemoteOrchestrator").d("Unknown event: %s", event.type)
                    }
                }
            }
        } catch (e: CancellationException) {
            // Stop button / screen teardown - not a run failure; propagate.
            throw e
        } catch (e: Exception) {
            Timber.tag("RemoteOrchestrator").e(e, "Remote run failed")
            emit(OrchestratorEvent.Failed(e.message ?: "Remote gateway error"))
        }
    }.flowOn(dispatchers.io)

    /**
     * Ask the PC to stop the run currently streaming to this phone, if any.
     * Fire-and-forget; safe to call when nothing is running.
     */
    fun stopActiveRun() {
        val runId = activeRunId ?: return
        Timber.tag("RemoteOrchestrator").i("Stopping remote run=%s", runId)
        stopScope.launch {
            runCatching { gatewayClient.stopRun(runId) }
                .onFailure { Timber.tag("RemoteOrchestrator").w(it, "Failed to stop run=%s", runId) }
        }
    }

    /**
     * Parse a tool call from the gateway's event payload.
     *
     * The gateway sends `arguments` as a JSON string (the same shape the LLM
     * produced). We parse it into a `Map<String, JsonElement>` so the
     * existing [ToolCall] domain model can carry it unchanged.
     */
    private fun parseToolCall(callId: String, name: String, argumentsJson: String): ToolCall {
        val arguments: Map<String, kotlinx.serialization.json.JsonElement> = if (argumentsJson.isBlank()) {
            emptyMap()
        } else {
            runCatching {
                json.parseToJsonElement(argumentsJson) as? JsonObject
            }.getOrNull()?.toMap() ?: emptyMap()
        }
        return ToolCall(
            id = callId.ifBlank { UUID.randomUUID().toString() },
            name = name,
            arguments = arguments,
        )
    }
}
