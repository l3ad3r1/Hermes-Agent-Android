package com.hermes.agent.data.remote

import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin-client [ConversationRepository] backed by the PC Hermes gateway's
 * Sessions API.
 *
 * The PC is the canonical conversation store. This implementation:
 *   - Polls the gateway for the session list and message history (the
 *     gateway doesn't push, so we poll at a fixed interval).
 *   - Maps [RemoteSession] / [RemoteMessage] into the domain
 *     [Conversation] / [Message] models the UI already renders.
 *   - Treats `addMessage` as a no-op: the PC persists messages when a run
 *     is sent via the chat flow, so the phone never writes directly.
 *
 * Seamless handoff: because the phone's conversation list IS the PC's
 * session list, a turn typed on the PC appears on the phone after the
 * next poll, and vice versa. No sync layer, no split-brain.
 */
@Singleton
class RemoteConversationRepository @Inject constructor(
    private val gatewayClient: GatewayApiClient,
    private val dispatchers: DispatcherProvider,
) : ConversationRepository {

    /** Poll interval for the conversation list and message history. */
    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
        val ALLOWED_ROLES = setOf(MessageRole.USER, MessageRole.ASSISTANT)
    }

    override fun observeConversations(): Flow<List<Conversation>> = flow {
        while (true) {
            // Emit only on success: a transient poll failure must not wipe
            // the list off screen (a getOrDefault(emptyList()) would blank it).
            runCatching { fetchConversations() }
                .onSuccess { emit(it) }
                .onFailure { Timber.tag("RemoteConvRepo").w(it, "polling sessions failed") }
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(dispatchers.io)

    override fun observeConversation(id: String): Flow<Conversation?> = flow {
        while (true) {
            runCatching { fetchConversation(id) }
                .onSuccess { emit(it) }
                .onFailure { Timber.tag("RemoteConvRepo").w(it, "polling session %s failed", id) }
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(dispatchers.io)

    override fun observeMessages(conversationId: String): Flow<List<Message>> = flow {
        while (true) {
            runCatching { fetchMessages(conversationId) }
                .onSuccess { emit(it) }
                .onFailure { Timber.tag("RemoteConvRepo").w(it, "polling messages for %s failed", conversationId) }
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(dispatchers.io)

    override suspend fun createConversation(title: String): String {
        // Try to create on the gateway; if it fails (400, network error,
        // etc.), fall back to a local UUID. The RemoteOrchestrator will
        // pass this ID as session_id to startRun, and the gateway creates
        // the session on demand when the first run is submitted.
        return runCatching { gatewayClient.createSession(title).id }
            .onFailure { Timber.tag("RemoteConvRepo").w(it, "createSession failed — using local UUID") }
            .getOrElse { java.util.UUID.randomUUID().toString() }
    }

    override suspend fun ensureConversation(id: String, title: String) {
        // The PC gateway creates sessions on demand when a run references
        // them, so ensureConversation is a best-effort no-op: try to fetch,
        // and create if it doesn't exist.
        runCatching {
            gatewayClient.getSession(id)
        }.onFailure {
            runCatching { gatewayClient.createSession(title) }
        }
    }

    override suspend fun addMessage(conversationId: String, message: Message): String {
        // No-op: the PC persists messages as part of the run flow.
        // RemoteChatRepository sends the user message via the Runs API,
        // and the gateway stores it in the session transcript.
        return message.id
    }

    override suspend fun renameConversation(id: String, title: String) {
        gatewayClient.updateSession(id, title)
    }

    override suspend fun deleteConversation(id: String) {
        gatewayClient.deleteSession(id)
    }

    override suspend fun getRecentMessages(conversationId: String, limit: Int): List<Message> {
        return fetchMessages(conversationId).takeLast(limit)
    }

    override suspend fun rewindTo(conversationId: String, message: Message): Int {
        // The PC gateway's Sessions API doesn't expose a rewind endpoint.
        // Returning 0 is honest: the phone can't rewind a remote session.
        // A fork+delete could approximate it but changes the session ID,
        // which would break the UI's current navigation. Deferred.
        Timber.tag("RemoteConvRepo").w("rewindTo is not supported in remote mode")
        return 0
    }

    override suspend fun forkFrom(conversationId: String, message: Message, title: String): String {
        val forked = gatewayClient.forkSession(conversationId, title)
        return forked.id
    }

    // ── Fetch helpers ─────────────────────────────────────────────────────

    private suspend fun fetchConversations(): List<Conversation> =
        gatewayClient.listSessions().map { it.toConversation() }

    private suspend fun fetchConversation(id: String): Conversation? =
        runCatching { gatewayClient.getSession(id).toConversation() }.getOrNull()

    private suspend fun fetchMessages(conversationId: String): List<Message> =
        gatewayClient.getSessionMessages(conversationId)
            // The gateway stores tool results and system instructions as
            // separate messages in the session transcript. The phone is a
            // thin chat client — only user and assistant turns belong in the
            // chat UI. Tool output (e.g. raw ls listings, file listings as
            // JSON) would otherwise appear as assistant-style bubbles.
            .filter { it.toMessage(conversationId).role in ALLOWED_ROLES }
            .map { it.toMessage(conversationId) }

    // ── Mappers ───────────────────────────────────────────────────────────

    private fun RemoteSession.toConversation(): Conversation = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        messageCount = messageCount,
    )

    private fun RemoteMessage.toMessage(conversationId: String): Message = Message(
        id = id,
        conversationId = conversationId,
        role = MessageRole.fromWire(role),
        content = content,
        agentRole = if (role.equals("assistant", ignoreCase = true)) com.hermes.agent.domain.model.AgentRole.DEFAULT else null,
        timestamp = timestamp,
        isOnDevice = false,
    )
}