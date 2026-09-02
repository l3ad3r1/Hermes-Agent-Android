package com.hermes.agent.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hermes.agent.data.llm.stripLeadingRoleLabel
import com.hermes.agent.data.local.dao.ActivityLedgerDao
import com.hermes.agent.data.local.dao.AgentTaskDao
import com.hermes.agent.data.local.dao.BookmarkDao
import com.hermes.agent.data.local.dao.CalendarEventDao
import com.hermes.agent.data.local.dao.ConnectorDao
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.DocumentChunkDao
import com.hermes.agent.data.local.dao.DocumentDao
import com.hermes.agent.data.local.dao.ExecutionPlanDao
import com.hermes.agent.data.local.dao.KanbanTicketDao
import com.hermes.agent.data.local.dao.MemoryDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.dao.NoteDao
import com.hermes.agent.data.local.dao.ScheduledTaskDao
import com.hermes.agent.data.local.dao.PromptRevisionDao
import com.hermes.agent.data.local.dao.SkillRevisionDao
import com.hermes.agent.data.local.dao.SupplementalPromptDao
import com.hermes.agent.data.local.dao.TodoTaskDao
import com.hermes.agent.data.local.dao.MoodEntryDao
import com.hermes.agent.data.local.dao.ScriptPluginDao
import com.hermes.agent.data.local.dao.SkillDao
import com.hermes.agent.data.local.entity.PromptRevisionEntity
import com.hermes.agent.data.local.entity.SkillRevisionEntity
import com.hermes.agent.data.local.entity.SupplementalPromptEntity
import com.hermes.agent.data.local.entity.ActivityLedgerEntity
import com.hermes.agent.data.local.entity.AgentTaskEntity
import com.hermes.agent.data.local.entity.BookmarkEntity
import com.hermes.agent.data.local.entity.CalendarEventEntity
import com.hermes.agent.data.local.entity.ConnectorEntity
import com.hermes.agent.data.local.entity.ConversationEntity
import com.hermes.agent.data.local.entity.DocumentChunkEntity
import com.hermes.agent.data.local.entity.DocumentEntity
import com.hermes.agent.data.local.entity.ExecutionPlanEntity
import com.hermes.agent.data.local.entity.ExecutionStepEntity
import com.hermes.agent.data.local.entity.KanbanTicketEntity
import com.hermes.agent.data.local.entity.MemoryEntity
import com.hermes.agent.data.local.entity.MessageEntity
import com.hermes.agent.data.local.entity.NoteEntity
import com.hermes.agent.data.local.entity.ScheduledTaskEntity
import com.hermes.agent.data.local.entity.SkillEntity
import com.hermes.agent.data.local.entity.TodoTaskEntity
import com.hermes.agent.data.local.dao.McpDao
import com.hermes.agent.data.local.dao.PresenceLogDao
import com.hermes.agent.data.local.entity.McpServerEntity
import com.hermes.agent.data.local.entity.McpToolEntity
import com.hermes.agent.data.local.entity.MoodEntryEntity
import com.hermes.agent.data.local.entity.PresenceLogEntity
import com.hermes.agent.data.local.entity.ScriptPluginEntity

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
        SkillRevisionEntity::class,
        SupplementalPromptEntity::class,
        PromptRevisionEntity::class,
        KanbanTicketEntity::class,
        ExecutionPlanEntity::class,
        ExecutionStepEntity::class,
        ActivityLedgerEntity::class,
        NoteEntity::class,
        TodoTaskEntity::class,
        CalendarEventEntity::class,
        BookmarkEntity::class,
        MoodEntryEntity::class,
        ScriptPluginEntity::class,
        McpServerEntity::class,
        McpToolEntity::class,
        PresenceLogEntity::class,
    ],
    version = 23,
    exportSchema = true,
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
    abstract fun skillRevisionDao(): SkillRevisionDao
    abstract fun supplementalPromptDao(): SupplementalPromptDao
    abstract fun promptRevisionDao(): PromptRevisionDao
    abstract fun kanbanTicketDao(): KanbanTicketDao
    abstract fun executionPlanDao(): ExecutionPlanDao
    abstract fun activityLedgerDao(): ActivityLedgerDao
    abstract fun noteDao(): NoteDao
    abstract fun todoTaskDao(): TodoTaskDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun moodEntryDao(): MoodEntryDao
    abstract fun scriptPluginDao(): ScriptPluginDao
    abstract fun mcpDao(): McpDao
    abstract fun presenceLogDao(): PresenceLogDao

    companion object {
        const val DATABASE_NAME = "hermes.db"

        /**
         * Build the conversation search index from scratch.
         *
         * FTS4, not FTS5. No Android release enables `SQLITE_ENABLE_FTS5` — the
         * flag is absent from AOSP's SQLite build on every branch from android10
         * through android16 — so `USING fts5` raises "no such module: fts5"
         * everywhere, not just on old devices.
         *
         * Called from three places, because none of them covers the others:
         *  - [MIGRATION_7_8], the original upgrade path;
         *  - [MIGRATION_10_11], for installs already past that point;
         *  - the `onCreate` callback in `DatabaseModule`, because this table is
         *    not a Room entity, so a fresh install builds its schema without
         *    running a single migration and would otherwise never get it.
         *
         * Idempotent by construction: it drops and rebuilds, so running it more
         * than once on the same database is safe and leaves no duplicate rows.
         */
        fun createSearchIndex(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TRIGGER IF EXISTS conversation_fts_ai")
            db.execSQL("DROP TRIGGER IF EXISTS conversation_fts_ad")
            db.execSQL("DROP TRIGGER IF EXISTS conversation_fts_au")
            db.execSQL("DROP TABLE IF EXISTS conversation_fts")

            // Only title and messages are searchable; the rest are carried for
            // the join and ordering. Indexing the timestamps would let a query
            // like "2026" match every conversation through its epoch digits.
            db.execSQL(
                """
                CREATE VIRTUAL TABLE conversation_fts USING fts4(
                    id,
                    title,
                    messages,
                    created_at,
                    updated_at,
                    notindexed=id,
                    notindexed=created_at,
                    notindexed=updated_at
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO conversation_fts (id, title, messages, created_at, updated_at)
                SELECT c.id, c.title, GROUP_CONCAT(m.content, ' '), c.created_at, c.updated_at
                FROM conversations c
                LEFT JOIN messages m ON c.id = m.conversation_id
                GROUP BY c.id
                """.trimIndent()
            )

            // Each trigger deletes the conversation's row before reinserting it.
            // "INSERT OR REPLACE" cannot work here: an FTS table has no unique
            // constraint on `id`, so the conflict never fires and every edit
            // would append another copy of the same conversation.
            fun syncTrigger(name: String, event: String, table: String, idExpr: String) = """
                CREATE TRIGGER IF NOT EXISTS $name AFTER $event ON $table
                BEGIN
                    DELETE FROM conversation_fts WHERE id = $idExpr;
                    INSERT INTO conversation_fts (id, title, messages, created_at, updated_at)
                    SELECT c.id, c.title, GROUP_CONCAT(m.content, ' '), c.created_at, c.updated_at
                    FROM conversations c
                    LEFT JOIN messages m ON c.id = m.conversation_id
                    WHERE c.id = $idExpr
                    GROUP BY c.id;
                END
            """.trimIndent()

            db.execSQL(syncTrigger("conversation_fts_ai", "INSERT", "messages", "NEW.conversation_id"))
            db.execSQL(syncTrigger("conversation_fts_ad", "DELETE", "messages", "OLD.conversation_id"))
            db.execSQL(syncTrigger("conversation_fts_au", "UPDATE", "conversations", "NEW.id"))
        }

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
                // Phase 5.1: conversation search index.
                //
                // This originally created the table with "USING fts5", which
                // no Android release supports, so any install that actually
                // reached this migration failed to open its database at all.
                // Rebuilt through the shared FTS4 helper.
                createSearchIndex(db)
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS execution_plans (
                        id TEXT NOT NULL PRIMARY KEY,
                        conversationId TEXT NOT NULL,
                        userMessage TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        approved INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_execution_plans_conversationId_createdAt " +
                        "ON execution_plans(conversationId, createdAt)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS execution_steps (
                        id TEXT NOT NULL PRIMARY KEY,
                        planId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        agentRoleName TEXT NOT NULL,
                        description TEXT NOT NULL,
                        requiredToolsJson TEXT NOT NULL,
                        dependsOnJson TEXT NOT NULL,
                        statusName TEXT NOT NULL,
                        startedAt INTEGER,
                        finishedAt INTEGER,
                        toolCallIdsJson TEXT NOT NULL,
                        errorMessage TEXT,
                        FOREIGN KEY(planId) REFERENCES execution_plans(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_execution_steps_planId ON execution_steps(planId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_execution_steps_planId_position " +
                        "ON execution_steps(planId, position)",
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS activity_ledger (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        timestamp INTEGER NOT NULL,
                        kindName TEXT NOT NULL,
                        origin TEXT NOT NULL,
                        conversationId TEXT,
                        title TEXT NOT NULL,
                        detail TEXT NOT NULL,
                        success INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_activity_ledger_timestamp " +
                        "ON activity_ledger(timestamp)",
                )
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

        /**
         * Scrubs "Assistant:" prefixes the on-device model left in stored replies.
         *
         * Until the local prompt was fixed, the model was shown a role-labelled
         * transcript and continued it, writing its own "Assistant:" line. Those
         * replies were persisted with the prefix, so fixing the prompt only
         * cleans new turns — existing conversations keep the text and go on
         * showing it, including in the list preview.
         *
         * Uses the same [stripLeadingRoleLabel] the provider applies to fresh
         * replies, so the cleanup and the prevention cannot drift apart. It
         * rewrites only rows that actually change, and rebuilds the search index
         * afterwards because the indexed text has moved.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Collected first, then written: updating while the cursor over
                // the same table is still open is asking for trouble.
                val messageEdits = mutableListOf<Pair<String, String>>()
                db.query("SELECT id, content FROM messages WHERE role = 'assistant'").use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val content = c.getString(1)
                        val cleaned = stripLeadingRoleLabel(content)
                        if (cleaned != content) messageEdits += id to cleaned
                    }
                }
                messageEdits.forEach { (id, cleaned) ->
                    db.execSQL("UPDATE messages SET content = ? WHERE id = ?", arrayOf(cleaned, id))
                }

                val previewEdits = mutableListOf<Pair<String, String>>()
                db.query("SELECT id, last_message_preview FROM conversations").use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val preview = c.getString(1)
                        val cleaned = stripLeadingRoleLabel(preview)
                        if (cleaned != preview) previewEdits += id to cleaned
                    }
                }
                previewEdits.forEach { (id, cleaned) ->
                    db.execSQL(
                        "UPDATE conversations SET last_message_preview = ? WHERE id = ?",
                        arrayOf(cleaned, id),
                    )
                }

                if (messageEdits.isNotEmpty() || previewEdits.isNotEmpty()) {
                    createSearchIndex(db)
                }
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Installs sitting at v10 never got a search index: MIGRATION_7_8
                // could not succeed (it asked for FTS5), and a fresh install
                // builds its schema from the entity list, which this table is
                // not part of. Build it here.
                createSearchIndex(db)
            }
        }


        /**
         * Schema version 13:
         * 1) Skill revision history table (`skill_revisions`)
         * 2) Supplemental prompts table (`supplemental_prompts`)
         * 3) Supplemental prompt revision history table (`prompt_revisions`)
         * 4) `messages.evidence_state`, backing [MessageEntity.evidenceState]
         *
         * The column ships here rather than in a later migration because 13 has
         * never been released: the only build in the field is 0.9.3 at version
         * 12, so there is no database anywhere that stopped at an intermediate
         * 13 without it.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nullable with no default, matching the entity: an older row
                // simply has no recorded evidence state.
                db.execSQL("ALTER TABLE messages ADD COLUMN evidence_state TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS skill_revisions (
                        id TEXT NOT NULL PRIMARY KEY,
                        skillId TEXT NOT NULL,
                        skillName TEXT NOT NULL,
                        version TEXT NOT NULL,
                        description TEXT NOT NULL,
                        content TEXT NOT NULL,
                        note TEXT NOT NULL,
                        replacedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_skill_revisions_skillId_replacedAt " +
                        "ON skill_revisions(skillId, replacedAt)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS supplemental_prompts (
                        roleName TEXT NOT NULL PRIMARY KEY,
                        content TEXT NOT NULL,
                        version TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS prompt_revisions (
                        id TEXT NOT NULL PRIMARY KEY,
                        roleName TEXT NOT NULL,
                        version TEXT NOT NULL,
                        content TEXT NOT NULL,
                        note TEXT NOT NULL,
                        replacedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_prompt_revisions_roleName_replacedAt " +
                        "ON prompt_revisions(roleName, replacedAt)",
                )

                // Not every v12 database has a search index. MIGRATION_11_12
                // only rebuilt it when it actually rewrote a row, so an install
                // that reached 12 with nothing to scrub arrived without one,
                // and searching Chats then fails with "no such table:
                // conversation_fts". Rebuilding here is idempotent.
                createSearchIndex(db)
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notes (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        category TEXT NOT NULL DEFAULT 'general',
                        isStarred INTEGER NOT NULL DEFAULT 0,
                        folder TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_category ON notes(category)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes(updatedAt)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS todo_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL DEFAULT '',
                        done INTEGER NOT NULL DEFAULT 0,
                        priority TEXT NOT NULL DEFAULT 'MEDIUM',
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        dueDateMs INTEGER,
                        reminderText TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        completedAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_todo_tasks_done ON todo_tasks(done)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_todo_tasks_dueDateMs ON todo_tasks(dueDateMs)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS calendar_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        sourceCalendar TEXT NOT NULL DEFAULT 'default',
                        startMs INTEGER NOT NULL,
                        endMs INTEGER NOT NULL,
                        allDay INTEGER NOT NULL DEFAULT 0,
                        location TEXT,
                        reminderMinutes INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_events_startMs ON calendar_events(startMs)")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        id TEXT NOT NULL PRIMARY KEY,
                        url TEXT NOT NULL,
                        title TEXT NOT NULL DEFAULT '',
                        note TEXT NOT NULL DEFAULT '',
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mood_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        dateMs INTEGER NOT NULL,
                        mood TEXT NOT NULL DEFAULT 'MID',
                        intensity INTEGER NOT NULL DEFAULT 5,
                        note TEXT NOT NULL DEFAULT '',
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_url ON bookmarks(url)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mood_entries_dateMs ON mood_entries(dateMs)")
            }
        }

        /**
         * Adds the installed-module table for script plugins. The manifest is
         * stored as fetched, alongside the exact permissions the user approved
         * for that snapshot.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS script_plugins (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        version TEXT NOT NULL,
                        author TEXT NOT NULL,
                        description TEXT NOT NULL,
                        manifestJson TEXT NOT NULL,
                        grantedPermissions TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        installedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_script_plugins_enabled ON script_plugins(enabled)")
            }
        }

        /**
         * Adds multimodal attachment columns to messages (Phase 2: Vision).
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachment_uri TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachment_mime_type TEXT DEFAULT NULL")
            }
        }

        /**
         * Adds MCP schema cache and server registry tables (Phase 4: MCP Client).
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mcp_servers (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        url TEXT NOT NULL,
                        transport TEXT NOT NULL,
                        headersJson TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastError TEXT DEFAULT NULL,
                        supportsParallelCalls INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mcp_tools (
                        serverId TEXT NOT NULL,
                        toolName TEXT NOT NULL,
                        qualifiedName TEXT NOT NULL,
                        description TEXT NOT NULL,
                        inputSchemaJson TEXT NOT NULL,
                        PRIMARY KEY (serverId, toolName)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Adds provenance and lint status columns to skills (Phase 6: Skills Hub).
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE skills ADD COLUMN sourceUrl TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE skills ADD COLUMN pinnedCommit TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE skills ADD COLUMN installedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE skills ADD COLUMN lintStatus TEXT DEFAULT NULL")
            }
        }

        /**
         * Adds presence logs and ambient context snapshots (Phase 5: Presence).
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS presence_logs (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestamp INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        locationName TEXT,
                        batteryLevel INTEGER NOT NULL,
                        isCharging INTEGER NOT NULL,
                        networkType TEXT NOT NULL DEFAULT 'UNKNOWN',
                        activity TEXT NOT NULL DEFAULT 'UNKNOWN',
                        screenOn INTEGER NOT NULL DEFAULT 0,
                        contextSummary TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_presence_logs_timestamp ON presence_logs(timestamp)")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop plaintext coordinates latitude & longitude to enforce privacy invariants
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `presence_logs_new` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `timestamp` INTEGER NOT NULL,
                        `locationName` TEXT,
                        `batteryLevel` INTEGER NOT NULL,
                        `isCharging` INTEGER NOT NULL,
                        `networkType` TEXT NOT NULL DEFAULT 'UNKNOWN',
                        `activity` TEXT NOT NULL DEFAULT 'UNKNOWN',
                        `screenOn` INTEGER NOT NULL DEFAULT 0,
                        `contextSummary` TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `presence_logs_new` (`id`, `timestamp`, `locationName`, `batteryLevel`, `isCharging`, `networkType`, `activity`, `screenOn`, `contextSummary`)
                    SELECT `id`, `timestamp`, `locationName`, `batteryLevel`, `isCharging`, `networkType`, `activity`, `screenOn`, `contextSummary` FROM `presence_logs`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `presence_logs`")
                db.execSQL("ALTER TABLE `presence_logs_new` RENAME TO `presence_logs`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_presence_logs_timestamp` ON `presence_logs` (`timestamp`)")
            }
        }

        /**
         * The search index is not a Room entity, so nothing in Room's own
         * machinery guarantees it exists: a database can arrive at the current
         * version without one — most easily by restoring a backup taken from an
         * install that never had it, which runs no migration at all. Checked on
         * every open so that path self-heals rather than failing at the first
         * search.
         */
        fun ensureSearchIndex(db: SupportSQLiteDatabase) {
            val present = db.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'conversation_fts'",
            ).use { it.moveToFirst() }
            if (!present) createSearchIndex(db)
        }
    }
}
