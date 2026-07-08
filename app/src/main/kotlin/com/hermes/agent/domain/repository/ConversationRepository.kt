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

    /**
     * Ensure a conversation with [id] exists, creating an empty one if it
     * doesn't. Idempotent — an existing conversation is left untouched.
     *
     * The new-chat flow navigates to `chat/{uuid}` with a client-generated id
     * before any row exists; this guarantees the parent row is present so the
     * first [addMessage] doesn't fail the message→conversation foreign key.
     */
    suspend fun ensureConversation(id: String, title: String = "New conversation")

    /** Append a message to a conversation. Returns the message id. */
    suspend fun addMessage(conversationId: String, message: Message): String

    /** Rename a conversation. */
    suspend fun renameConversation(id: String, title: String)

    /** Delete a conversation and all of its messages. */
    suspend fun deleteConversation(id: String)

    /** Return the most recent N messages for a conversation, oldest-first. */
    suspend fun getRecentMessages(conversationId: String, limit: Int = 30): List<Message>
}
