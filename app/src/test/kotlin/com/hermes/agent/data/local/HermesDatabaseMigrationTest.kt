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
}
