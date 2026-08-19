package com.hermes.agent.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrade path for the on-device database.
 *
 * This exists because the failure it catches is invisible everywhere else. A
 * hand-written migration that does not match what Room generates for the
 * entities compiles cleanly, passes every unit test, and installs fine — it
 * only fails when a real user's existing database is opened, as an identity
 * hash mismatch. A clean install cannot catch it either, because Room builds
 * the schema from the entity list through `onCreate` and runs no migrations at
 * all.
 *
 * `runMigrationsAndValidate` is the part that matters: it applies the migration
 * and then compares the resulting schema against Room's own exported
 * expectation, so a column, type, nullability, primary key or index that drifts
 * from the entity fails here rather than on a phone.
 */
@RunWith(AndroidJUnit4::class)
class HermesDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HermesDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate12To13_createsTheNewTablesAndKeepsExistingData() {
        val conversationId = "migration-test-conversation"

        helper.createDatabase(TEST_DB, 12).use { db ->
            // A row written by the old schema. If the upgrade were destructive
            // the schema would still look correct afterwards and only this
            // would reveal it, so it is the assertion that actually matters.
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, title, created_at, updated_at, last_message_preview, message_count)
                VALUES (?, 'before upgrade', 1, 1, 'survived', 0)
                """.trimIndent(),
                arrayOf<Any>(conversationId),
            )
        }

        // `validateDroppedTables = false`: the search index is a hand-built FTS4
        // virtual table, deliberately outside Room's entity list, and the strict
        // check rejects any table it does not recognise. Every entity table is
        // still validated against the exported schema, which is the point.
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 13, false, HermesDatabase.MIGRATION_12_13,
        )

        db.query(
            "SELECT title FROM conversations WHERE id = ?", arrayOf<Any>(conversationId),
        ).use { cursor ->
            assertTrue("the pre-upgrade conversation must survive", cursor.moveToFirst())
            assertEquals("before upgrade", cursor.getString(0))
        }

        listOf("skill_revisions", "supplemental_prompts", "prompt_revisions").forEach { table ->
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf<Any>(table),
            ).use { cursor ->
                assertTrue("migration must create $table", cursor.moveToFirst())
            }
        }

        // Not a Room entity, so `runMigrationsAndValidate` above cannot see it
        // missing: a v12 database that arrived without a search index kept
        // crossing into 13 without one, and the first search then failed with
        // "no such table: conversation_fts".
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'conversation_fts'",
        ).use { cursor ->
            assertTrue("migration must leave a search index behind", cursor.moveToFirst())
        }

        listOf(
            "index_skill_revisions_skillId_replacedAt",
            "index_prompt_revisions_roleName_replacedAt",
        ).forEach { index ->
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
                arrayOf<Any>(index),
            ).use { cursor ->
                assertTrue("migration must create $index", cursor.moveToFirst())
            }
        }
    }

    private companion object {
        const val TEST_DB = "hermes-migration-test.db"
    }
}
