package com.hermes.agent.domain.model

/** What kind of action a ledger row records. */
enum class ActivityKind {
    TOOL_CALL,
    DELEGATION,
    PROACTIVE,
}

/**
 * One row of the "What Jeeves did" ledger: a tool execution or a delegated
 * background task, with enough detail to audit what happened without
 * reconstructing the whole conversation.
 */
data class ActivityEntry(
    val id: Long = 0,
    val timestamp: Long,
    val kind: ActivityKind,
    /** "interactive" or "background" — mirrors [com.hermes.agent.domain.agent.ExecutionOrigin]. */
    val origin: String,
    val conversationId: String?,
    /** Tool name or delegated-task label. */
    val title: String,
    /** Result summary, denial reason, or failure text. */
    val detail: String,
    val success: Boolean,
)
