package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to conversation threads and their messages.
 *
 * Implementations:
 *   - [com.hermes.agent.data.repository.ConversationRepositoryImpl] backed by Room.
 *
 * Phase 1 surface area: list / get / create / delete / add-message / rename.
 * Phase 2 will add: search across conversations, archive, pin, export.
 */
interface ConversationRepository {

    /** Hot stream of all conversations, most-recently-updated first. */
    fun observeConversations(): Flow<List<Conversation>>

    /** Hot stream of a single conversation, or null if it doesn't exist. */
    fun observeConversation(id: String): Flow<Conversation?>

    /** Hot stream of all messages in a conversation, oldest-first. */
    fun observeMessages(conversationId: String): Flow<List<Message>>

    /** Create a new empty conversation and return its id. */
    suspend fun createConversation(title: String = "New conversation"): String

    /** Append a message to a conversation. Returns the message id. */
    suspend fun addMessage(conversationId: String, message: Message): String

    /** Rename a conversation. */
    suspend fun renameConversation(id: String, title: String)

    /** Delete a conversation and all of its messages. */
    suspend fun deleteConversation(id: String)

    /** Return the most recent N messages for a conversation, oldest-first. */
    suspend fun getRecentMessages(conversationId: String, limit: Int = 30): List<Message>
}
