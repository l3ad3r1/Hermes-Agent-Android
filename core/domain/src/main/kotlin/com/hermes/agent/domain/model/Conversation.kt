package com.hermes.agent.domain.model

/**
 * A conversation thread.
 *
 * Phase 1 stores conversations as flat lists of messages. Phase 2 will add
 * per-agent context windows (Section 6.1) and the dual-store memory system
 * (Section 6.2) on top of this same model.
 *
 * @property id Stable unique identifier (UUID).
 * @property title Human-readable title. Defaults to "New conversation" until
 *   the first user message is sent, after which the conversational agent
 *   suggests a short title (Phase 2 feature; for now the first ~60 chars of
 *   the first user message is used).
 * @property createdAt Wall-clock millis when the conversation was created.
 * @property updatedAt Wall-clock millis of the most recent message.
 * @property lastMessagePreview Truncated preview of the most recent message,
 *   used in the conversations list.
 * @property messageCount Total number of messages in the conversation.
 */
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String = "",
    val messageCount: Int = 0,
)
