package com.hermes.agent.data.repository

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
)
