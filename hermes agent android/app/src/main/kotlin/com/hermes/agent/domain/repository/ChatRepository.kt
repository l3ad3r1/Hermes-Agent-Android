package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.ChatStreamEvent
import com.hermes.agent.domain.agent.OrchestratorEvent

/**
 * High-level chat façade used by [com.hermes.agent.ui.chat.ChatViewModel].
 *
 * The default implementation composes [ConversationRepository] (persistence),
 * the [com.hermes.agent.domain.agent.Orchestrator] (multi-agent execution),
 * and the [com.hermes.agent.data.settings.SettingsRepository] (cloud toggle,
 * API key).
 *
 * Two streaming APIs are exposed:
 *   - [sendMessage] — Phase 1 simple flow, emits [ChatStreamEvent]s only.
 *     Retained for backward compatibility and for callers that don't need
 *     agent orchestration metadata.
 *   - [sendMessageOrchestrated] — Phase 2 rich flow, emits [OrchestratorEvent]s
 *     including the execution plan, tool calls, and per-step transitions.
 *     The chat UI uses this to render the agent-role badge, tool-call cards,
 *     and the live plan indicator.
 */
interface ChatRepository {

    /**
     * Phase 1 streaming send. Persists the user message, runs the LLM
     * pipeline (Phase 1: direct provider call; Phase 2: still works but
     * bypasses the orchestrator), and streams the assistant reply.
     */
    fun sendMessage(
        conversationId: String,
        content: String,
    ): kotlinx.coroutines.flow.Flow<ChatStreamEvent>

    /**
     * Phase 2 streaming send. Persists the user message, runs the full
     * orchestrator pipeline (intent classification → plan → tool calls →
     * streaming reply), and emits rich [OrchestratorEvent]s the UI can
     * use to render agent activity.
     *
     * The final assistant message is persisted before
     * [OrchestratorEvent.ReplyComplete] is emitted.
     */
    fun sendMessageOrchestrated(
        conversationId: String,
        content: String,
    ): kotlinx.coroutines.flow.Flow<OrchestratorEvent>
}
