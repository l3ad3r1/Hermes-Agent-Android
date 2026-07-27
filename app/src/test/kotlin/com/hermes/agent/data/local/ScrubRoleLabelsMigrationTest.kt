package com.hermes.agent.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HermesDatabase.MIGRATION_11_12] scrubs the "Assistant:" prefixes the local
 * model left behind before its prompt was fixed.
 *
 * Seeded with the exact shape seen on the device, including the stacked
 * double prefix that appeared once a contaminated reply was replayed as
 * history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScrubRoleLabelsMigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
        }
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                ApplicationProvider.getApplicationContext(),
            ).name(null).callback(callback).build(),
        )
        db = helper.writableDatabase

        db.execSQL(
            """
            CREATE TABLE conversations (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_message_preview TEXT NOT NULL DEFAULT '',
                message_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT NOT NULL PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    @After
    fun tearDown() = helper.close()

    private fun addConversation(id: String, preview: String) {
        db.execSQL(
            "INSERT INTO conversations (id, title, created_at, updated_at, last_message_preview) " +
                "VALUES (?, ?, 1, 1, ?)",
            arrayOf<Any>(id, "New conversation", preview),
        )
    }

    private fun addMessage(id: String, conversationId: String, role: String, content: String) {
        db.execSQL(
            "INSERT INTO messages (id, conversation_id, role, content, timestamp) VALUES (?, ?, ?, ?, 1)",
            arrayOf<Any>(id, conversationId, role, content),
        )
    }

    private fun content(id: String): String {
        db.query("SELECT content FROM messages WHERE id = ?", arrayOf(id)).use {
            it.moveToFirst()
            return it.getString(0)
        }
    }

    private fun preview(id: String): String {
        db.query("SELECT last_message_preview FROM conversations WHERE id = ?", arrayOf(id)).use {
            it.moveToFirst()
            return it.getString(0)
        }
    }

    @Test
    fun `strips stored prefixes without touching anything else`() {
        addConversation("c1", "Assistant:\nI'm doing well.")
        addMessage("m1", "c1", "user", "how are you")
        addMessage("m2", "c1", "assistant", "Assistant:\nI'm doing well.")
        // The stacked form, from a contaminated reply replayed as history.
        addMessage("m3", "c1", "assistant", "Assistant:\nAssistant:\nIt's great to have you here.")
        // A clean reply must survive untouched.
        addMessage("m4", "c1", "assistant", "Rivers are natural flowing bodies of water.")
        // Prose that merely contains the word is not a prefix.
        addMessage("m5", "c1", "assistant", "The assistant: a short history.")
        // User turns are never rewritten, even if they look like one.
        addMessage("m6", "c1", "user", "Assistant: is what I typed")

        HermesDatabase.MIGRATION_11_12.migrate(db)

        assertEquals("I'm doing well.", content("m2"))
        assertEquals("It's great to have you here.", content("m3"))
        assertEquals("Rivers are natural flowing bodies of water.", content("m4"))
        assertEquals("The assistant: a short history.", content("m5"))
        assertEquals("Assistant: is what I typed", content("m6"))
        assertEquals("I'm doing well.", preview("c1"))
    }

    @Test
    fun `rebuilds the search index so it matches the scrubbed text`() {
        addConversation("c1", "Assistant:\nvolcanoes erupt")
        addMessage("m1", "c1", "assistant", "Assistant:\nvolcanoes erupt")

        HermesDatabase.MIGRATION_11_12.migrate(db)

        // The index carries message text, so leaving it stale after a rewrite
        // would let search hit words that are no longer stored.
        db.query("SELECT messages FROM conversation_fts WHERE id = 'c1'").use {
            assertTrue("search index was not rebuilt", it.moveToFirst())
            assertEquals("volcanoes erupt", it.getString(0))
        }
    }

    @Test
    fun `is safe on a database that needs no cleaning`() {
        addConversation("c1", "All good here.")
        addMessage("m1", "c1", "assistant", "All good here.")

        HermesDatabase.MIGRATION_11_12.migrate(db)

        assertEquals("All good here.", content("m1"))
        assertEquals("All good here.", preview("c1"))
    }
}
