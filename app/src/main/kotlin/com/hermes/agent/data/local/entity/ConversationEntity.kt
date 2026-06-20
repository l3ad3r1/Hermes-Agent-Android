package com.hermes.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted conversation row. Maps 1:1 to [com.hermes.agent.domain.model.Conversation].
 */
@Entity(
    tableName = "conversations",
    indices = [Index("updated_at")]
)
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String = "",

    @ColumnInfo(name = "message_count")
    val messageCount: Int = 0,
)
