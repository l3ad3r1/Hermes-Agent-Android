package com.hermes.agent.data.local.entity

/**
 * Wrapper for session with its message count.
 * Used in UI to show message count badge.
 */
data class SessionWithMessageCount(
    val session: ConversationEntity,
    val messageCount: Int,
)