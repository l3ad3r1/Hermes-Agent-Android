package com.hermes.agent.ui.chat

import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.Message

/**
 * Immutable UI state for [ChatScreen].
 *
 * The streaming assistant reply is rendered as a synthetic "in-flight"
 * message appended to the list — once the stream completes, it is
 * replaced by the persisted [Message] emitted by the repository.
 */
data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val streamingText: String? = null,
    val streamingIsOnDevice: Boolean = true,
    val streamingAgentRole: AgentRole? = null,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val title: String = "New conversation",
    /** Most recent execution plan (Phase 2). Null when no plan has been
     *  emitted for the current turn. */
    val currentPlan: PlanSummary? = null,
    /** Tool calls observed during the current turn. Cleared on the next
     *  [sendMessage]. */
    val toolCalls: List<ToolCallSummary> = emptyList(),
    /** Phase 3: text that should prefill the input bar (e.g. from voice). */
    val inputPrefill: String = "",
    /** Phase 3: true while voice input is listening. */
    val isListening: Boolean = false,
    /** Estimated token count across all messages in this conversation. */
    val estimatedTokens: Int = 0,
    /** Active model name shown in the status bar. */
    val activeModel: String = "",
    /** True when the LLM is running on-device. */
    val isOnDevice: Boolean = true,
    /** Set when the agent's `clarify` tool is awaiting an answer from the
     *  user. The UI renders the question (+ optional choices) above the input
     *  bar; answering resumes the suspended tool. */
    val pendingClarification: ClarificationRequest? = null,
    /** The agent's current `todo` plan, shown to the user as a live checklist.
     *  Empty when the agent hasn't created one. */
    val todos: List<TodoItem> = emptyList(),
) {
    /** Messages plus the in-flight streaming bubble, if any. */
    val visibleItems: List<ChatListItem>
        get() = buildList {
            addAll(messages.map { ChatListItem.MessageItem(it) })
            streamingText?.let { streaming ->
                add(
                    ChatListItem.StreamingItem(
                        text = streaming,
                        isOnDevice = streamingIsOnDevice,
                        agentRole = streamingAgentRole,
                        toolCalls = toolCalls,
                    )
                )
            }
        }
}

/** A clarifying question the agent is waiting on the user to answer. */
data class ClarificationRequest(
    val question: String,
    val choices: List<String>,
)

/** One item of the agent's live todo plan. [status] is one of
 *  pending / in_progress / completed / cancelled. */
data class TodoItem(
    val id: String,
    val content: String,
    val status: String,
)

/** Slimmed-down view of an [com.hermes.agent.domain.model.ExecutionPlan]. */
data class PlanSummary(
    val steps: List<PlanStepSummary>,
    val currentStepIndex: Int,
)

data class PlanStepSummary(
    val description: String,
    val agentRole: AgentRole,
    val status: StepStatus,
)

enum class StepStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }

/** Slimmed-down view of a [com.hermes.agent.data.llm.ToolCall] + result. */
data class ToolCallSummary(
    val name: String,
    val argumentsPreview: String,
    val status: ToolCallStatus,
    val outputPreview: String?,
)

enum class ToolCallStatus { PENDING, RUNNING, SUCCEEDED, FAILED }

sealed class ChatListItem {
    abstract val isOnDevice: Boolean

    data class MessageItem(val message: Message) : ChatListItem() {
        override val isOnDevice: Boolean get() = message.isOnDevice
    }

    data class StreamingItem(
        val text: String,
        override val isOnDevice: Boolean,
        val agentRole: AgentRole?,
        val toolCalls: List<ToolCallSummary>,
    ) : ChatListItem()
}
