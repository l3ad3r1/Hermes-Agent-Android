package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hermes.agent.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY timestamp ASC
        """
    )
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun recentByConversation(
        conversationId: String,
        limit: Int,
    ): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity): Long

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun countForConversation(conversationId: String): Int

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String): Int
}
