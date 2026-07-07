package com.hermes.agent.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hermes.agent.data.local.dao.AgentTaskDao
import com.hermes.agent.data.local.dao.ConnectorDao
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.DocumentChunkDao
import com.hermes.agent.data.local.dao.DocumentDao
import com.hermes.agent.data.local.dao.KanbanTicketDao
import com.hermes.agent.data.local.dao.MemoryDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.dao.ScheduledTaskDao
import com.hermes.agent.data.local.dao.SkillDao
import com.hermes.agent.data.local.entity.AgentTaskEntity
import com.hermes.agent.data.local.entity.ConnectorEntity
import com.hermes.agent.data.local.entity.ConversationEntity
import com.hermes.agent.data.local.entity.DocumentChunkEntity
import com.hermes.agent.data.local.entity.DocumentEntity
import com.hermes.agent.data.local.entity.KanbanTicketEntity
import com.hermes.agent.data.local.entity.MemoryEntity
import com.hermes.agent.data.local.entity.MessageEntity
import com.hermes.agent.data.local.entity.ScheduledTaskEntity
import com.hermes.agent.data.local.entity.SkillEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        DocumentEntity::class,
        DocumentChunkEntity::class,
        ScheduledTaskEntity::class,
        ConnectorEntity::class,
        AgentTaskEntity::class,
        SkillEntity::class,
        KanbanTicketEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun documentDao(): DocumentDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun connectorDao(): ConnectorDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun skillDao(): SkillDao
    abstract fun kanbanTicketDao(): KanbanTicketDao

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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS skills (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        version TEXT NOT NULL DEFAULT '1.0.0',
                        content TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT 'general',
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        isBuiltIn INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_skills_name ON skills(name)")
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN cronExpression TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS kanban_tickets (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'TODO',
                        assignee TEXT,
                        createdBy TEXT NOT NULL DEFAULT 'hermes',
                        priority TEXT NOT NULL DEFAULT 'MEDIUM',
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        result TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        completedAt INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_kanban_tickets_status ON kanban_tickets(status)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        // Conditional skill activation + curator lifecycle (v0.7.23).
                        db.execSQL("ALTER TABLE skills ADD COLUMN requiresToolsJson TEXT NOT NULL DEFAULT '[]'")
                        db.execSQL("ALTER TABLE skills ADD COLUMN fallbackForToolsJson TEXT NOT NULL DEFAULT '[]'")
                        db.execSQL("ALTER TABLE skills ADD COLUMN lifecycleState TEXT NOT NULL DEFAULT 'ACTIVE'")
                        db.execSQL("ALTER TABLE skills ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE skills ADD COLUMN useCount INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE skills ADD COLUMN lastUsedAt INTEGER")
                    }
                }

                val MIGRATION_7_8 = object : Migration(7, 8) {
                            override fun migrate(db: SupportSQLiteDatabase) {
                                // Phase 5.1: FTS5-powered session search (Hermes Agent parity).
                                // Creates standalone FTS5 virtual table (no contentEntity to avoid KSP issues).
                                db.execSQL("""
                                    CREATE VIRTUAL TABLE IF NOT EXISTS conversation_fts USING fts5(
                                        id,
                                        title,
                                        messages,
                                        created_at,
                                        updated_at
                                    )
                                """)
                                // Populate FTS index with existing data.
                                db.execSQL("""
                                    INSERT INTO conversation_fts (id, title, messages, created_at, updated_at)
                                    SELECT 
                                        c.id,
                                        c.title,
                                        GROUP_CONCAT(m.content, ' ') as messages,
                                        c.created_at,
                                        c.updated_at
                                    FROM conversations c
                                    LEFT JOIN messages m ON c.id = m.conversation_id
                                    GROUP BY c.id
                                """)
                                // Trigger: keep FTS index in sync on message insert.
                                db.execSQL("""
                                    CREATE TRIGGER IF NOT EXISTS conversation_fts_ai AFTER INSERT ON messages
                                    BEGIN
                                        INSERT OR REPLACE INTO conversation_fts (id, title, messages, created_at, updated_at)
                                        SELECT 
                                            c.id,
                                            c.title,
                                            GROUP_CONCAT(m.content, ' '),
                                            c.created_at,
                                            c.updated_at
                                        FROM conversations c
                                        JOIN messages m ON c.id = m.conversation_id
                                        WHERE c.id = NEW.conversation_id
                                        GROUP BY c.id
                                    END
                                """)
                                // Trigger: keep FTS index in sync on message delete.
                                db.execSQL("""
                                    CREATE TRIGGER IF NOT EXISTS conversation_fts_ad AFTER DELETE ON messages
                                    BEGIN
                                        INSERT OR REPLACE INTO conversation_fts (id, title, messages, created_at, updated_at)
                                        SELECT 
                                            c.id,
                                            c.title,
                                            GROUP_CONCAT(m.content, ' '),
                                            c.created_at,
                                            c.updated_at
                                        FROM conversations c
                                        JOIN messages m ON c.id = m.conversation_id
                                        WHERE c.id = OLD.conversation_id
                                        GROUP BY c.id
                                    END
                                """)
                                // Trigger: keep FTS index in sync on conversation update.
                                db.execSQL("""
                                    CREATE TRIGGER IF NOT EXISTS conversation_fts_au AFTER UPDATE ON conversations
                                    BEGIN
                                        INSERT OR REPLACE INTO conversation_fts (id, title, messages, created_at, updated_at)
                                        SELECT 
                                            c.id,
                                            c.title,
                                            GROUP_CONCAT(m.content, ' '),
                                            c.created_at,
                                            c.updated_at
                                        FROM conversations c
                                        JOIN messages m ON c.id = m.conversation_id
                                        WHERE c.id = NEW.id
                                        GROUP BY c.id
                                    END
                                """)
                            }
                        }

                val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS connectors (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        configJson TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        statusName TEXT NOT NULL,
                        result TEXT,
                        createdAt INTEGER NOT NULL,
                        startedAt INTEGER,
                        completedAt INTEGER
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
