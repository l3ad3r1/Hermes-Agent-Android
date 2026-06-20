package com.hermes.agent.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.DocumentChunkDao
import com.hermes.agent.data.local.dao.DocumentDao
import com.hermes.agent.data.local.dao.MemoryDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.dao.ScheduledTaskDao
import com.hermes.agent.data.local.entity.ConversationEntity
import com.hermes.agent.data.local.entity.DocumentChunkEntity
import com.hermes.agent.data.local.entity.DocumentEntity
import com.hermes.agent.data.local.entity.MemoryEntity
import com.hermes.agent.data.local.entity.MessageEntity
import com.hermes.agent.data.local.entity.ScheduledTaskEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        DocumentEntity::class,
        DocumentChunkEntity::class,
        ScheduledTaskEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun documentDao(): DocumentDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao

    companion object {
        const val DATABASE_NAME = "hermes.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS documents (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        source_uri TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        chunk_count INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_created_at ON documents(created_at)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_chunks (
                        id TEXT NOT NULL PRIMARY KEY,
                        document_id TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        embedding BLOB,
                        token_count INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_document_id ON document_chunks(document_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_document_id_ordinal ON document_chunks(document_id, ordinal)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        scheduleName TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        lastRunAt INTEGER,
                        lastResult TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
