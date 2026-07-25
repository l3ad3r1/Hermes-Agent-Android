package com.hermes.agent.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
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
 * Exercises [HermesDatabase.createSearchIndex] against a real SQLite database.
 *
 * Runs at the project's minSdk. The FTS5 module this index previously asked for
 * is absent from every Android release, so the version matters: the point of
 * this suite is that the DDL works on the oldest thing we ship to.
 *
 * Room itself is not involved — the tables are created by hand, so the test
 * pins the search SQL rather than Room's schema generation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SearchIndexTest {

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
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    @After
    fun tearDown() {
        helper.close()
    }

    private fun addConversation(id: String, title: String, updatedAt: Long = 1L) {
        db.execSQL(
            "INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(id, title, 1L, updatedAt),
        )
    }

    private fun addMessage(conversationId: String, content: String) {
        db.execSQL(
            "INSERT INTO messages (conversation_id, role, content, timestamp) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(conversationId, "user", content, 1L),
        )
    }

    private fun search(query: String): List<String> {
        val cursor = db.query(
            """
            SELECT c.id FROM conversation_fts f
            JOIN conversations c ON f.id = c.id
            WHERE conversation_fts MATCH ?
            ORDER BY c.updated_at DESC
            """.trimIndent(),
            arrayOf(query),
        )
        val ids = mutableListOf<String>()
        cursor.use { while (it.moveToNext()) ids += it.getString(0) }
        return ids
    }

    private fun ftsRowCount(id: String): Int {
        val cursor = db.query("SELECT COUNT(*) FROM conversation_fts WHERE id = ?", arrayOf(id))
        cursor.use { it.moveToFirst(); return it.getInt(0) }
    }

    @Test
    fun `builds the index and finds existing conversations`() {
        addConversation("c1", "Ocean notes")
        addMessage("c1", "rivers flow into the sea")
        addConversation("c2", "Mountains")
        addMessage("c2", "granite and snow")

        HermesDatabase.createSearchIndex(db)

        assertEquals(listOf("c1"), search("rivers"))
        assertEquals(listOf("c2"), search("granite"))
        // Titles are indexed too, not just message bodies.
        assertEquals(listOf("c1"), search("Ocean"))
    }

    @Test
    fun `triggers keep the index current`() {
        addConversation("c1", "Notes")
        HermesDatabase.createSearchIndex(db)
        assertTrue("nothing should match yet", search("volcano").isEmpty())

        addMessage("c1", "volcano formation")
        assertEquals("insert trigger did not update the index", listOf("c1"), search("volcano"))
    }

    @Test
    fun `a conversation is never indexed twice`() {
        addConversation("c1", "Notes")
        HermesDatabase.createSearchIndex(db)

        // Regression guard. The original triggers used INSERT OR REPLACE, which
        // cannot fire on an FTS table — `id` carries no unique constraint — so
        // every message appended another copy of the whole conversation.
        addMessage("c1", "first")
        addMessage("c1", "second")
        addMessage("c1", "third")

        assertEquals("conversation indexed more than once", 1, ftsRowCount("c1"))
        assertEquals(listOf("c1"), search("first"))
        assertEquals(listOf("c1"), search("third"))
    }

    @Test
    fun `rebuilding is safe and leaves no duplicates`() {
        addConversation("c1", "Notes")
        addMessage("c1", "reindex me")

        // Both the 7-to-8 and 10-to-11 migrations call this, and an upgrade that
        // crosses both would run it twice against the same database.
        HermesDatabase.createSearchIndex(db)
        HermesDatabase.createSearchIndex(db)

        assertEquals(1, ftsRowCount("c1"))
        assertEquals(listOf("c1"), search("reindex"))
    }

    @Test
    fun `supports the query syntax the tool advertises`() {
        addConversation("c1", "Docker", updatedAt = 10L)
        addMessage("c1", "docker networking bridge mode")
        addConversation("c2", "Kubernetes", updatedAt = 20L)
        addMessage("c2", "kubernetes networking policy")

        HermesDatabase.createSearchIndex(db)

        // Implicit AND.
        assertEquals(listOf("c1"), search("docker networking"))
        // Phrase.
        assertEquals(listOf("c1"), search("\"networking bridge\""))
        // Prefix wildcard.
        assertEquals(listOf("c1"), search("bridg*"))
        // OR, newest first.
        assertEquals(listOf("c2", "c1"), search("docker OR kubernetes"))
    }

    @Test
    fun `timestamps are not searchable`() {
        // created_at and updated_at are notindexed, so a bare number must not
        // drag in every conversation that happens to contain it in an epoch.
        addConversation("c1", "Notes", updatedAt = 1753400000000L)
        addMessage("c1", "nothing numeric here")

        HermesDatabase.createSearchIndex(db)

        assertTrue(search("1753400000000").isEmpty())
    }
}
