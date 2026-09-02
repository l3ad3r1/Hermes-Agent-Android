package com.hermes.agent.data.local

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HermesDatabaseMigrationTest {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun closeDatabase() {
        helper?.close()
    }

    @Test
    fun `migration 8 to 9 creates durable plan schema`() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext(),
        ).name(null).callback(object : SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
            override fun onUpgrade(
                db: androidx.sqlite.db.SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }).build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val database = checkNotNull(helper).writableDatabase

        HermesDatabase.MIGRATION_8_9.migrate(database)

        val tables = mutableSetOf<String>()
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name IN ('execution_plans', 'execution_steps')",
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        assertEquals(setOf("execution_plans", "execution_steps"), tables)

        val indices = mutableSetOf<String>()
        database.query("PRAGMA index_list('execution_steps')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) indices += cursor.getString(nameIndex)
        }
        assertTrue("index_execution_steps_planId" in indices)
        assertTrue("index_execution_steps_planId_position" in indices)

        database.query("PRAGMA foreign_key_list('execution_steps')").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("execution_plans", cursor.getString(cursor.getColumnIndexOrThrow("table")))
        }
    }

    @Test
    fun `migration 9 to 10 creates the activity ledger`() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext(),
        ).name(null).callback(object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
            override fun onUpgrade(
                db: androidx.sqlite.db.SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }).build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val database = checkNotNull(helper).writableDatabase

        HermesDatabase.MIGRATION_9_10.migrate(database)

        val tables = mutableSetOf<String>()
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'activity_ledger'",
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        assertEquals(setOf("activity_ledger"), tables)

        val indices = mutableSetOf<String>()
        database.query("PRAGMA index_list('activity_ledger')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) indices += cursor.getString(nameIndex)
        }
        assertTrue("index_activity_ledger_timestamp" in indices)
    }

    @Test
    fun `migrations 13 to 17 create module tables matching Room entities`() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext(),
        ).name(null).callback(object : SupportSQLiteOpenHelper.Callback(13) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
            override fun onUpgrade(
                db: androidx.sqlite.db.SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }).build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val database = checkNotNull(helper).writableDatabase

        HermesDatabase.MIGRATION_13_14.migrate(database)
        HermesDatabase.MIGRATION_14_15.migrate(database)
        HermesDatabase.MIGRATION_15_16.migrate(database)
        HermesDatabase.MIGRATION_16_17.migrate(database)

        val expectedColumns = mapOf(
            "notes" to setOf(
                "id", "title", "content", "tagsJson", "category", "isStarred",
                "folder", "createdAt", "updatedAt",
            ),
            "todo_tasks" to setOf(
                "id", "title", "body", "done", "priority", "tagsJson", "dueDateMs",
                "reminderText", "createdAt", "updatedAt", "completedAt",
            ),
            "calendar_events" to setOf(
                "id", "title", "description", "sourceCalendar", "startMs", "endMs",
                "allDay", "location", "reminderMinutes", "createdAt",
            ),
            "bookmarks" to setOf("id", "url", "title", "note", "tagsJson", "createdAt"),
            "mood_entries" to setOf(
                "id", "dateMs", "mood", "intensity", "note", "tagsJson", "createdAt",
            ),
        )

        expectedColumns.forEach { (table, expected) ->
            val actual = mutableSetOf<String>()
            database.query("PRAGMA table_info('$table')").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) actual += cursor.getString(nameIndex)
            }
            assertEquals("Unexpected columns for $table", expected, actual)
        }

        // Indices are validated by Room on every upgrade, and a mismatch here is
        // invisible to a fresh install: `createAllTables` builds from the entity
        // list, so only an upgrading device sees the migration's version. These
        // seven were created by the migrations while no entity declared them,
        // which fails the upgrade with "Migration didn't properly handle".
        val expectedIndices = mapOf(
            "notes" to setOf("index_notes_category", "index_notes_updatedAt"),
            "todo_tasks" to setOf("index_todo_tasks_done", "index_todo_tasks_dueDateMs"),
            "calendar_events" to setOf("index_calendar_events_startMs"),
            "bookmarks" to setOf("index_bookmarks_url"),
            "mood_entries" to setOf("index_mood_entries_dateMs"),
        )
        expectedIndices.forEach { (table, expected) ->
            val actual = mutableSetOf<String>()
            database.query("PRAGMA index_list('$table')").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    // Skip the implicit indices SQLite builds for primary keys;
                    // Room ignores those too. (`PRAGMA index_list` here has no
                    // `origin` column, so the name prefix is the filter.)
                    if (!name.startsWith("sqlite_autoindex_")) actual += name
                }
            }
            assertEquals("Unexpected indices for $table", expected, actual)
        }
    }

    @Test
    fun `migration 18 to 19 adds attachment columns to messages`() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext(),
        ).name(null).callback(object : SupportSQLiteOpenHelper.Callback(18) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        conversation_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        agent_role TEXT,
                        timestamp INTEGER NOT NULL,
                        tokens INTEGER NOT NULL DEFAULT 0,
                        is_on_device INTEGER NOT NULL DEFAULT 1,
                        evidence_state TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("INSERT INTO messages (id, conversation_id, role, content, timestamp) VALUES ('msg-1', 'conv-1', 'user', 'hello', 1000)")
            }

            override fun onUpgrade(
                db: androidx.sqlite.db.SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }).build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val database = checkNotNull(helper).writableDatabase

        HermesDatabase.MIGRATION_18_19.migrate(database)

        val columns = mutableSetOf<String>()
        database.query("PRAGMA table_info('messages')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        assertTrue("attachment_uri" in columns)
        assertTrue("attachment_mime_type" in columns)

        database.query("SELECT attachment_uri, attachment_mime_type FROM messages WHERE id = 'msg-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
    }

    @Test
    fun `migration 19 to 20 creates mcp schema`() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext(),
        ).name(null).callback(object : SupportSQLiteOpenHelper.Callback(19) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
            override fun onUpgrade(
                db: androidx.sqlite.db.SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }).build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val database = checkNotNull(helper).writableDatabase

        HermesDatabase.MIGRATION_19_20.migrate(database)

        val tables = mutableSetOf<String>()
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name IN ('mcp_servers', 'mcp_tools')",
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        assertEquals(setOf("mcp_servers", "mcp_tools"), tables)

        val serverCols = mutableSetOf<String>()
        database.query("PRAGMA table_info('mcp_servers')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) serverCols += cursor.getString(nameIndex)
        }
        assertTrue("transport" in serverCols)
        assertTrue("headersJson" in serverCols)

        val toolCols = mutableSetOf<String>()
        database.query("PRAGMA table_info('mcp_tools')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) toolCols += cursor.getString(nameIndex)
        }
        assertTrue("qualifiedName" in toolCols)
        assertTrue("inputSchemaJson" in toolCols)
    }

    @Test
    fun `migration 20 to 21 adds provenance and lint columns to skills`() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext(),
        ).name(null).callback(object : SupportSQLiteOpenHelper.Callback(20) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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
                        updatedAt INTEGER NOT NULL,
                        requiresToolsJson TEXT NOT NULL DEFAULT '[]',
                        fallbackForToolsJson TEXT NOT NULL DEFAULT '[]',
                        lifecycleState TEXT NOT NULL DEFAULT 'ACTIVE',
                        lastUsedAt INTEGER,
                        useCount INTEGER NOT NULL DEFAULT 0,
                        pinned INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
            override fun onUpgrade(
                db: androidx.sqlite.db.SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }).build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val database = checkNotNull(helper).writableDatabase

        HermesDatabase.MIGRATION_20_21.migrate(database)

        val columns = mutableSetOf<String>()
        database.query("PRAGMA table_info('skills')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        assertTrue("sourceUrl" in columns)
        assertTrue("pinnedCommit" in columns)
        assertTrue("installedAt" in columns)
        assertTrue("lintStatus" in columns)
    }

    @Test
    fun `migration 21 to 22 creates presence_logs schema`() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext(),
        ).name(null).callback(object : SupportSQLiteOpenHelper.Callback(21) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
            override fun onUpgrade(
                db: androidx.sqlite.db.SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }).build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val database = checkNotNull(helper).writableDatabase

        HermesDatabase.MIGRATION_21_22.migrate(database)

        val tables = mutableSetOf<String>()
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'presence_logs'",
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        assertEquals(setOf("presence_logs"), tables)

        val expectedColumns = setOf(
            "id", "timestamp", "latitude", "longitude", "locationName",
            "batteryLevel", "isCharging", "networkType", "activity",
            "screenOn", "contextSummary"
        )
        val actualColumns = mutableSetOf<String>()
        database.query("PRAGMA table_info('presence_logs')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) actualColumns += cursor.getString(nameIndex)
        }
        assertEquals(expectedColumns, actualColumns)

        val indices = mutableSetOf<String>()
        database.query("PRAGMA index_list('presence_logs')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                if (!name.startsWith("sqlite_autoindex_")) indices += name
            }
        }
        assertTrue("index_presence_logs_timestamp" in indices)
    }

    @Test
    fun `migration 22 to 23 drops plaintext coordinates and preserves data`() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext(),
        ).name(null).callback(object : SupportSQLiteOpenHelper.Callback(22) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                HermesDatabase.MIGRATION_21_22.migrate(db)
            }
            override fun onUpgrade(
                db: androidx.sqlite.db.SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }).build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val database = checkNotNull(helper).writableDatabase

        // Insert row with legacy coordinates in v22
        database.execSQL(
            """
            INSERT INTO presence_logs (id, timestamp, latitude, longitude, locationName, batteryLevel, isCharging, networkType, activity, screenOn, contextSummary)
            VALUES ('test_id_1', 123456789, 37.7749, -122.4194, 'San Francisco', 80, 1, 'WIFI', 'STILL', 1, 'Summary')
            """.trimIndent()
        )

        // Migrate 22 -> 23
        HermesDatabase.MIGRATION_22_23.migrate(database)

        val actualColumns = mutableSetOf<String>()
        database.query("PRAGMA table_info('presence_logs')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) actualColumns += cursor.getString(nameIndex)
        }

        val expectedColumns = setOf(
            "id", "timestamp", "locationName", "batteryLevel",
            "isCharging", "networkType", "activity", "screenOn", "contextSummary"
        )
        assertEquals(expectedColumns, actualColumns)
        assertTrue("latitude must be dropped in v23", "latitude" !in actualColumns)
        assertTrue("longitude must be dropped in v23", "longitude" !in actualColumns)

        // Verify preserved data
        database.query("SELECT id, locationName, batteryLevel, isCharging FROM presence_logs WHERE id = 'test_id_1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("test_id_1", cursor.getString(0))
            assertEquals("San Francisco", cursor.getString(1))
            assertEquals(80, cursor.getInt(2))
            assertEquals(1, cursor.getInt(3))
        }

        // Verify index
        val indices = mutableSetOf<String>()
        database.query("PRAGMA index_list('presence_logs')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                if (!name.startsWith("sqlite_autoindex_")) indices += name
            }
        }
        assertTrue("index_presence_logs_timestamp" in indices)
    }
}

