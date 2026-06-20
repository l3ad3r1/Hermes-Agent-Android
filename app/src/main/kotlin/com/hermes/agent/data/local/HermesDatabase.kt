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
import com.hermes.agent.data.local.entity.ConversationEntity
import com.hermes.agent.data.local.entity.DocumentChunkEntity
import com.hermes.agent.data.local.entity.DocumentEntity
import com.hermes.agent.data.local.entity.MemoryEntity
import com.hermes.agent.data.local.entity.MessageEntity

/**
 * Hermes Agent local database.
 *
 * Phase 1 schema (version 1):
 *   - conversations
 *   - messages (FK → conversations, CASCADE delete)
 *   - memories (no embeddings yet)
 *
 * Phase 2 schema (version 2):
 *   - documents                  (new)
 *   - document_chunks            (new, FK → documents, CASCADE delete)
 *
 * Phase 3 will add:
 *   - sqlite_vss virtual table on memories.embedding
 *   - sqlite_vss virtual table on document_chunks.embedding
 *   - agent_runs table for orchestration tracing
 *
 * The database instance is provided by [com.hermes.agent.di.DatabaseModule].
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        DocumentEntity::class,
        DocumentChunkEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun documentDao(): DocumentDao
    abstract fun documentChunkDao(): DocumentChunkDao

    companion object {
        const val DATABASE_NAME = "hermes.db"

        /**
         * Phase 1 → Phase 2 migration: create the documents and
         * document_chunks tables. No data needs to be moved.
         */
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
    }
}
