package com.hermes.agent.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.data.local.HermesDatabase
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers what a restore is allowed to do to an existing install.
 *
 * These are the failure modes behind "restore doesn't work": an archive that
 * carried nothing recognisable still reported success and restarted the app, and
 * a log file left over from the current install was replayed over the restored
 * database.
 */
@RunWith(RobolectricTestRunner::class)
class LocalBackupManagerRestoreTest {

    private lateinit var context: Context
    private lateinit var manager: LocalBackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Restore only closes the database; it never queries it.
        val database = mockk<HermesDatabase>(relaxed = true)
        every { database.close() } returns Unit
        manager = LocalBackupManager(context, database)
    }

    private fun sqliteBytes(marker: String): ByteArray =
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + marker.toByteArray()

    private fun zipOf(entries: Map<String, ByteArray>): Uri {
        val zip = File(context.cacheDir, "backup_${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return Uri.fromFile(zip)
    }

    @Test
    fun `an archive without a database is rejected instead of silently doing nothing`() = runTest {
        val uri = zipOf(mapOf("holiday-photo.jpg" to byteArrayOf(1, 2, 3)))

        val result = manager.restoreFromZip(uri)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("not a Hermes backup"),
        )
    }

    @Test
    fun `an archive whose database is not SQLite is rejected`() = runTest {
        val uri = zipOf(mapOf("hermes.db" to "this is not a database".toByteArray()))

        val result = manager.restoreFromZip(uri)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("damaged"))
    }

    @Test
    fun `the database and every preferences file are restored`() = runTest {
        val uri = zipOf(
            mapOf(
                "hermes.db" to sqliteBytes("restored"),
                "hermes_settings.preferences_pb" to "settings".toByteArray(),
                // Exported all along, but the old restore only looked for
                // hermes_settings and dropped this one on the floor.
                "hermes_learning_state.preferences_pb" to "learning".toByteArray(),
            ),
        )

        val result = manager.restoreFromZip(uri)

        assertTrue(result.isSuccess)
        assertTrue(context.getDatabasePath("hermes.db").readBytes().contentEquals(sqliteBytes("restored")))
        val dataStore = File(context.filesDir, "datastore")
        assertEquals("settings", File(dataStore, "hermes_settings.preferences_pb").readText())
        assertEquals("learning", File(dataStore, "hermes_learning_state.preferences_pb").readText())
    }

    @Test
    fun `a log left by this install is removed when the archive has none`() = runTest {
        val wal = context.getDatabasePath("hermes.db-wal")
        wal.parentFile?.mkdirs()
        wal.writeBytes("writes belonging to the install being replaced".toByteArray())

        val result = manager.restoreFromZip(zipOf(mapOf("hermes.db" to sqliteBytes("restored"))))

        assertTrue(result.isSuccess)
        assertFalse("a stale -wal would replay over the restored database", wal.exists())
    }

    @Test
    fun `a rejected archive leaves the existing database untouched`() = runTest {
        val db = context.getDatabasePath("hermes.db")
        db.parentFile?.mkdirs()
        db.writeBytes(sqliteBytes("original"))

        val result = manager.restoreFromZip(zipOf(mapOf("notes.txt" to byteArrayOf(9))))

        assertTrue(result.isFailure)
        assertTrue(db.readBytes().contentEquals(sqliteBytes("original")))
    }

    @Test
    fun `an entry that escapes the archive root cannot be written outside the app`() = runTest {
        val uri = zipOf(
            mapOf(
                "hermes.db" to sqliteBytes("restored"),
                "../../../../evil.preferences_pb" to "escaped".toByteArray(),
            ),
        )

        val result = manager.restoreFromZip(uri)

        assertTrue(result.isSuccess)
        // Flattened to its base name, so it lands in the datastore folder.
        assertTrue(File(context.filesDir, "datastore/evil.preferences_pb").exists())
        assertFalse(File(context.filesDir.parentFile?.parentFile, "evil.preferences_pb").exists())
    }
}
