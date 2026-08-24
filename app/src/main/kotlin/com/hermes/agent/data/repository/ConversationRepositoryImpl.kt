package com.hermes.agent.data.repository
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.entity.ConversationEntity
import com.hermes.agent.data.local.entity.MessageEntity
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.util.DispatcherProvider
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val dispatchers: DispatcherProvider,
) : ConversationRepository {

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeConversation(id: String): Flow<Conversation?> =
        conversationDao.observeById(id).map { it?.toDomain() }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeByConversation(conversationId).map { rows ->
            rows.map { it.toDomain() }
        }

    override suspend fun createConversation(title: String): String = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val id = IdGenerator.newId()
        conversationDao.upsert(
            ConversationEntity(
                id = id,
                title = title,
                createdAt = now,
                updatedAt = now,
            )
        )
        id
    }

    override suspend fun ensureConversation(id: String, title: String): Unit = withContext(dispatchers.io) {
        if (conversationDao.getById(id) == null) {
            val now = System.currentTimeMillis()
            conversationDao.upsert(
                ConversationEntity(
                    id = id,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    override suspend fun addMessage(conversationId: String, message: Message): String =
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            val entity = MessageEntity(
                id = message.id,
                conversationId = conversationId,
                role = message.role.wireName,
                content = message.content,
                agentRole = message.agentRole?.name,
                timestamp = message.timestamp.takeIf { it > 0 } ?: now,
                tokens = message.tokens,
                isOnDevice = message.isOnDevice,
                evidenceState = message.evidenceState?.name,
            )
            messageDao.upsert(entity)
            conversationDao.touchAfterMessage(
                id = conversationId,
                updatedAt = now,
                preview = message.content.take(120),
                delta = 1,
            )
            message.id
        }

    override suspend fun renameConversation(id: String, title: String) = withContext(dispatchers.io) {
        conversationDao.rename(id, title)
        Unit
    }

    override suspend fun deleteConversation(id: String) = withContext(dispatchers.io) {
        // Messages cascade-delete via FK.
        conversationDao.delete(id)
        Unit
    }

    override suspend fun getRecentMessages(conversationId: String, limit: Int): List<Message> =
        withContext(dispatchers.io) {
            // DAO returns newest-first; flip to oldest-first for prompt construction.
            messageDao.recentByConversation(conversationId, limit).asReversed().map { it.toDomain() }
        }

    override suspend fun rewindTo(conversationId: String, message: Message): Int =
        withContext(dispatchers.io) {
            val removed = messageDao.deleteFrom(conversationId, message.timestamp)
            // The conversation's preview and count are denormalised onto the
            // row, so they have to be rebuilt from what actually survives or the
            // chat list keeps advertising a message the user just removed.
            val remaining = messageDao.recentByConversation(conversationId, 1).firstOrNull()
            conversationDao.touchAfterMessage(
                id = conversationId,
                updatedAt = System.currentTimeMillis(),
                preview = remaining?.content?.take(120).orEmpty(),
                delta = -removed,
            )
            removed
        }

    override suspend fun forkFrom(
        conversationId: String,
        message: Message,
        title: String,
    ): String = withContext(dispatchers.io) {
        val history = messageDao.messagesThrough(conversationId, message.timestamp)
        val newId = createConversation(title)
        // Fresh ids: a message id is unique per row, and reusing the originals
        // would make the fork and its source the same rows to Room.
        history.forEach { entity ->
            messageDao.upsert(
                entity.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    conversationId = newId,
                ),
            )
        }
        history.lastOrNull()?.let { last ->
            conversationDao.touchAfterMessage(
                id = newId,
                updatedAt = System.currentTimeMillis(),
                preview = last.content.take(120),
                delta = history.size,
            )
        }
        newId
    }
}

private fun ConversationEntity.toDomain() = Conversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessagePreview = lastMessagePreview,
    messageCount = messageCount,
)

private fun MessageEntity.toDomain() = Message(
    id = id,
    conversationId = conversationId,
    role = MessageRole.fromWire(role),
    content = content,
    agentRole = agentRole?.let { runCatching { AgentRole.valueOf(it) }.getOrNull() },
    timestamp = timestamp,
    tokens = tokens,
    isOnDevice = isOnDevice,
    evidenceState = evidenceState?.let { runCatching { com.hermes.agent.domain.model.EvidenceState.valueOf(it) }.getOrNull() },
)
