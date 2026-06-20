package com.hermes.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted message row. Maps 1:1 to [com.hermes.agent.domain.model.Message].
 *
 * Foreign key to [ConversationEntity] cascades deletes so the application
 * never has to manually clean up orphaned messages.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversation_id"),
        Index(value = ["conversation_id", "timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "role")
    val role: String,             // MessageRole.wireName

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "agent_role")
    val agentRole: String?,       // AgentRole.name, null for non-assistant messages

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "tokens")
    val tokens: Int = 0,

    @ColumnInfo(name = "is_on_device")
    val isOnDevice: Boolean = true,
)
