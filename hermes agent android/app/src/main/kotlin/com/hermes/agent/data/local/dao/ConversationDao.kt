package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.hermes.agent.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity): Long

    @Update
    suspend fun update(entity: ConversationEntity): Int

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String): Int

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query(
        """
        UPDATE conversations
        SET updated_at = :updatedAt,
            last_message_preview = :preview,
            message_count = message_count + :delta
        WHERE id = :id
        """
    )
    suspend fun touchAfterMessage(
        id: String,
        updatedAt: Long,
        preview: String,
        delta: Int,
    )
}
