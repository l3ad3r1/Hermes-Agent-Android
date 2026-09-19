package com.hermes.agent.data.export

import android.content.Context
import android.provider.Settings
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.data.local.entity.ConnectorEntity
import com.hermes.agent.data.local.entity.NoteEntity
import com.hermes.agent.data.local.entity.ScheduledTaskEntity
import com.hermes.agent.data.memory.LearningState
import com.hermes.agent.data.settings.PlaintextSecretCipher
import com.hermes.agent.data.settings.SettingsRepositoryImpl
import com.hermes.agent.domain.backup.BackupCipher
import com.hermes.agent.domain.backup.BackupStreamCipher
import com.hermes.agent.domain.model.ScheduledTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class FullBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val password = "correct horse battery staple"

    private lateinit var db: HermesDatabase
    private val settings = SettingsRepositoryImpl(context, PlaintextSecretCipher())
    private val learning = LearningState(context)

    @Before
    fun setUp() {
        context.deleteDatabase(HermesDatabase.DATABASE_NAME)
        db = openDb()
        File(context.filesDir, FullBackupFormat.PENDING_DIR).deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun openDb() = Room.databaseBuilder(context, HermesDatabase::class.java, HermesDatabase.DATABASE_NAME)
        .allowMainThreadQueries().build()

    private fun manager() = FullBackupManager(context, db, settings, learning)

    private fun note(id: String) = NoteEntity(
        id = id, title = "Note $id", content = "body", tagsJson = "[]", category = "c",
        isStarred = false, folder = null, createdAt = 1L, updatedAt = 2L,
    )

    private fun backupBytes(): ByteArray = ByteArrayOutputStream().also {
        runBlocking { manager().backup(it, password) }
    }.toByteArray()

    private fun restoreFrom(bytes: ByteArray) = runBlocking {
        manager().stageRestore(ByteArrayInputStream(bytes), password)
    }

    /** Closes the database the way a process restart would, then runs the next launch's step. */
    private fun relaunch() {
        db.close()
        PendingRestore.applyIfPending(context)
        db = openDb()
    }

    private fun populate() = runBlocking {
        db.noteDao().upsert(note("n1"))
        db.scheduledTaskDao().upsert(
            ScheduledTaskEntity.fromDomain(
                ScheduledTask(id = "cron1", label = "Morning", prompt = "Say hi", cronExpression = "0 8 * * *", createdAt = 5L),
            ),
        )
        db.connectorDao().upsert(
            ConnectorEntity(
                id = "conn1", name = "GitHub", type = "HTTP", configJson = "{}", isEnabled = true, createdAt = 3L, lastUsedAt = null,
            ),
        )
        settings.setCloudApiKey("sk-live-original")
        settings.setCloudBaseUrl("https://provider.example/v1")
        settings.setRemoteGatewayUrl("http://pc.ts.net:8642")
        settings.setRemoteGatewayApiKey("gw-secret")
        context.getSharedPreferences("local_bots", Context.MODE_PRIVATE).edit()
            .putString("chief_name", "Jarvis").putInt("count", 3).putBoolean("flag", true).commit()
        File(context.filesDir, "checkpoints").mkdirs()
        File(context.filesDir, "checkpoints/a.txt").writeText("checkpoint")
    }

    /** Puts the app in a different state, as a reinstall or later use would. */
    private fun scramble() = runBlocking {
        db.noteDao().delete("n1")
        db.scheduledTaskDao().delete("cron1")
        settings.setCloudApiKey("sk-something-else")
        settings.setRemoteGatewayUrl("http://elsewhere:1")
        context.getSharedPreferences("local_bots", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "checkpoints/a.txt").delete()
    }

    @Test
    fun `everything comes back after a restore on the next launch`() {
        populate()
        val bytes = backupBytes()
        scramble()

        val staged = restoreFrom(bytes)
        assertTrue(manager().isRestorePending())
        assertTrue(staged.sameDevice)
        assertTrue(staged.summary.databaseBytes > 0)

        relaunch()

        runBlocking {
            assertNotNull("chat/notes table", db.noteDao().getById("n1"))
            assertEquals("Morning", db.scheduledTaskDao().observeAll().first().single().label)
            assertEquals("GitHub", db.connectorDao().observeAll().first().single().name)
            val now = SettingsRepositoryImpl(context, PlaintextSecretCipher()).current()
            assertEquals("sk-live-original", now.cloudApiKey)
            assertEquals("https://provider.example/v1", now.cloudBaseUrl)
            assertEquals("http://pc.ts.net:8642", now.remoteGatewayUrl)
            assertEquals("gw-secret", now.remoteGatewayApiKey)
        }
        val prefs = context.getSharedPreferences("local_bots", Context.MODE_PRIVATE)
        assertEquals("Jarvis", prefs.getString("chief_name", null))
        assertEquals(3, prefs.getInt("count", 0))
        assertTrue(prefs.getBoolean("flag", false))
        assertEquals("checkpoint", File(context.filesDir, "checkpoints/a.txt").readText())

        val result = manager().lastRestoreResult()!!
        assertTrue(result.problems.joinToString(), result.ok)
        assertFalse("nothing left staged", manager().isRestorePending())
    }

    @Test
    fun `the old database is kept beside the restored one`() {
        populate()
        val bytes = backupBytes()
        runBlocking { db.noteDao().upsert(note("made-after-the-backup")) }

        restoreFrom(bytes)
        relaunch()

        val kept = context.getDatabasePath(HermesDatabase.DATABASE_NAME).let { File(it.parentFile, it.name + ".pre-restore") }
        assertTrue(kept.exists())
        runBlocking {
            assertNull("the restore is the backup's state", db.noteDao().getById("made-after-the-backup"))
            assertNotNull(db.noteDao().getById("n1"))
        }
    }

    @Test
    fun `nothing is applied until the next launch`() {
        populate()
        val bytes = backupBytes()
        scramble()

        restoreFrom(bytes)

        runBlocking {
            assertNull("the running app is untouched", db.noteDao().getById("n1"))
            assertEquals("sk-something-else", settings.current().cloudApiKey)
        }
    }

    @Test
    fun `a wrong password is reported and leaves nothing staged`() {
        populate()
        val bytes = backupBytes()

        val error = runCatching {
            runBlocking { manager().stageRestore(ByteArrayInputStream(bytes), "not the password") }
        }.exceptionOrNull()

        assertTrue(error is BackupCipher.WrongPasswordException)
        assertFalse(manager().isRestorePending())
        assertFalse(File(context.filesDir, FullBackupFormat.PENDING_DIR).exists())
    }

    @Test
    fun `a file that is not a full backup is refused`() {
        val error = runCatching {
            runBlocking { manager().stageRestore(ByteArrayInputStream("{\"schemaVersion\":1}".toByteArray()), password) }
        }.exceptionOrNull()

        assertTrue(error is BackupStreamCipher.CorruptBackupException)
        assertFalse(manager().isRestorePending())
    }

    @Test
    fun `a file with a path that escapes the staging folder is refused`() {
        val evil = ByteArrayOutputStream()
        BackupStreamCipher.encrypt(evil, password, 1_000).use { enc ->
            ZipOutputStream(enc).use { zip ->
                zip.putNextEntry(ZipEntry("../escaped.txt"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }

        val error = runCatching {
            runBlocking { manager().stageRestore(ByteArrayInputStream(evil.toByteArray()), password) }
        }.exceptionOrNull()

        assertTrue(error is BackupStreamCipher.CorruptBackupException)
        assertFalse(File(context.filesDir.parentFile, "escaped.txt").exists())
        assertFalse(File(context.filesDir, "escaped.txt").exists())
    }

    @Test
    fun `a backup that lost its end is refused, not applied in part`() {
        populate()
        val bytes = backupBytes()

        val error = runCatching {
            runBlocking { manager().stageRestore(ByteArrayInputStream(bytes.copyOf(bytes.size - 40)), password) }
        }.exceptionOrNull()

        assertNotNull(error)
        assertFalse(manager().isRestorePending())
    }

    @Test
    fun `a database newer than this app understands is refused`() {
        populate()
        db.close()
        val error = runCatching {
            DatabaseFiles.requireIntact(context.getDatabasePath(HermesDatabase.DATABASE_NAME), maxVersion = 1)
        }.exceptionOrNull()
        db = openDb()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("newer"))
    }

    @Test
    fun `downloaded models are left out and said so`() {
        File(context.filesDir, "models").mkdirs()
        File(context.filesDir, "models/big.gguf").writeText("weights")

        val summary = ByteArrayOutputStream().let { out -> runBlocking { manager().backup(out, password) } }

        assertTrue(summary.skipped.any { it.startsWith("models/") })
        assertFalse(summary.entries.any { it.contains("big.gguf") })
    }

    @Test
    fun `the tailscale sign-in comes back on the same phone`() {
        File(context.filesDir, "tailnet").mkdirs()
        File(context.filesDir, "tailnet/tailscaled.state").writeText("node-key")
        val bytes = backupBytes()
        File(context.filesDir, "tailnet/tailscaled.state").delete()

        restoreFrom(bytes)
        relaunch()

        assertEquals("node-key", File(context.filesDir, "tailnet/tailscaled.state").readText())
    }

    @Test
    fun `the tailscale sign-in is not copied onto a different phone`() {
        File(context.filesDir, "tailnet").mkdirs()
        File(context.filesDir, "tailnet/tailscaled.state").writeText("node-key")
        val bytes = backupBytes()
        File(context.filesDir, "tailnet/tailscaled.state").delete()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "a-different-phone")

        val staged = restoreFrom(bytes)
        assertFalse(staged.sameDevice)
        relaunch()

        assertFalse(File(context.filesDir, "tailnet/tailscaled.state").exists())
        assertTrue(manager().lastRestoreResult()!!.problems.any { it.contains("Tailscale") })
    }

    @Test
    fun `a setting nobody listed is still carried`() = runBlocking {
        val before = settings.exportRawPreferences().toMutableMap()
        before["some_future_setting"] = com.hermes.agent.domain.backup.RawPref("string", kotlinx.serialization.json.JsonPrimitive("kept"))
        settings.importRawPreferences(before)
        val bytes = backupBytes()
        settings.importRawPreferences(before - "some_future_setting")

        restoreFrom(bytes)
        relaunch()

        val after = SettingsRepositoryImpl(context, PlaintextSecretCipher()).exportRawPreferences()
        assertEquals(kotlinx.serialization.json.JsonPrimitive("kept"), after.getValue("some_future_setting").value)
    }

    @Test
    fun `the backup file does not contain readable secrets`() {
        populate()
        val text = String(backupBytes(), Charsets.ISO_8859_1)

        assertFalse(text.contains("sk-live-original"))
        assertFalse(text.contains("gw-secret"))
        assertFalse(text.contains("Morning"))
    }
}
