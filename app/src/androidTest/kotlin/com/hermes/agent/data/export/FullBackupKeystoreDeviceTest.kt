package com.hermes.agent.data.export

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.data.local.entity.ConnectorEntity
import com.hermes.agent.data.local.entity.ScheduledTaskEntity
import com.hermes.agent.data.memory.LearningState
import com.hermes.agent.data.security.EncryptedSettingsRepository
import com.hermes.agent.data.security.KeystoreManager
import com.hermes.agent.data.settings.SettingsRepositoryImpl
import com.hermes.agent.domain.model.ScheduledTask
import com.hermes.agent.domain.settings.CloudProviderProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID

/**
 * The parts of a full backup that unit tests can only fake: credentials through the real Android
 * Keystore, a real device id, and a real database file.
 *
 * Every secret here is generated in the test, so nothing real is ever used.
 *
 * It deletes every Keystore key the app owns to imitate a fresh install, so never run it against
 * an install with real data. To run it safely, temporarily change `applicationIdSuffix` in the
 * debug build type of `app/build.gradle.kts` from ".debug" to ".vtest" (an app id of its own, with
 * its own data and Keystore) and run:
 *
 *     ./gradlew :app:connectedDebugAndroidTest  *         -Pandroid.testInstrumentationRunnerArguments.class=com.hermes.agent.data.export.FullBackupKeystoreDeviceTest
 *
 * then put the suffix back. The run installs and removes the throwaway app by itself.
 */
@RunWith(AndroidJUnit4::class)
class FullBackupKeystoreDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "device-test-" + UUID.randomUUID()

    private val keystore = KeystoreManager()
    private val plain = SettingsRepositoryImpl(context)
    private val settings = EncryptedSettingsRepository(plain, keystore)
    private val learning = LearningState(context)
    private lateinit var db: HermesDatabase

    private fun openDb() = Room.databaseBuilder(context, HermesDatabase::class.java, HermesDatabase.DATABASE_NAME)
        .allowMainThreadQueries().build()

    private fun manager() = FullBackupManager(context, db, settings, learning)

    @Before
    fun setUp() {
        context.deleteDatabase(HermesDatabase.DATABASE_NAME)
        db = openDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** What a new install has: none of the old Keystore keys, no stored values, no data. */
    private fun becomeFreshInstall() = runBlocking {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.aliases().toList().forEach { store.deleteEntry(it) }
        plain.importRawPreferences(emptyMap())
        db.close()
        context.deleteDatabase(HermesDatabase.DATABASE_NAME)
        File(context.filesDir, "tailnet").deleteRecursively()
        context.getSharedPreferences("tailnet", Context.MODE_PRIVATE).edit().clear().commit()
        db = openDb()
    }

    @Test
    fun credentialsSurviveAReinstallOnTheRealKeystore() = runBlocking {
        val cloud = "sk-" + UUID.randomUUID()
        val gateway = "gw-" + UUID.randomUUID()
        val telegram = "tg-" + UUID.randomUUID()
        val provider = "pk-" + UUID.randomUUID()
        val connectorSecret = "conn-" + UUID.randomUUID()
        val nodeKey = "node-" + UUID.randomUUID()

        settings.setCloudApiKey(cloud)
        settings.setRemoteGatewayApiKey(gateway)
        settings.setTelegramBotToken(telegram)
        settings.setRemoteGatewayUrl("http://pc.example.ts.net:8642")
        settings.setCloudProviderProfiles(
            listOf(
                CloudProviderProfile(
                    id = "p1", name = "Provider", baseUrl = "https://p.example/v1", model = "m",
                    apiKey = provider, quality = 0.5, cost = 0.5, latency = 0.5, toolReliability = 0.5,
                ),
            ),
        )
        db.connectorDao().upsert(
            ConnectorEntity(
                id = "c1", name = "Connector", type = "HTTP", configJson = """{"token":"$connectorSecret"}""",
                isEnabled = true, createdAt = 1L, lastUsedAt = null,
            ),
        )
        db.scheduledTaskDao().upsert(
            ScheduledTaskEntity.fromDomain(
                ScheduledTask(id = "t1", label = "Job", prompt = "p", cronExpression = "0 8 * * *", createdAt = 2L),
            ),
        )
        File(context.filesDir, "tailnet").mkdirs()
        File(context.filesDir, "tailnet/tailscaled.state").writeText(nodeKey)
        context.getSharedPreferences("tailnet", Context.MODE_PRIVATE).edit().putBoolean("autostart", true).commit()

        // Sanity: they read back on the install that wrote them.
        assertEquals(cloud, settings.current().cloudApiKey)
        assertEquals(provider, settings.current().cloudProviderProfiles.single().apiKey)

        val bytes = ByteArrayOutputStream().also { manager().backup(it, password) }.toByteArray()
        val raw = String(bytes, Charsets.ISO_8859_1)
        listOf(cloud, gateway, telegram, provider, connectorSecret, nodeKey).forEach {
            assertFalse("a secret is readable in the backup file", raw.contains(it))
        }

        // A new install cannot read the old install's ciphertext. Prove the test means something:
        // with the Keystore keys gone, the stored credentials are unreadable.
        run {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.aliases().toList().forEach { store.deleteEntry(it) }
            assertEquals("the old ciphertext must be unreadable without its key", "", settings.current().cloudApiKey)
        }

        becomeFreshInstall()
        assertEquals("", settings.current().cloudApiKey)
        assertEquals(0, db.connectorDao().observeAll().first().size)

        val staged = manager().stageRestore(ByteArrayInputStream(bytes), password)
        assertTrue("the same phone is recognised by its real device id", staged.sameDevice)
        db.close()
        PendingRestore.applyIfPending(context)
        db = openDb()

        val result = manager().lastRestoreResult()!!
        assertTrue(result.problems.joinToString(), result.ok)

        val now = settings.current()
        assertEquals(cloud, now.cloudApiKey)
        assertEquals(gateway, now.remoteGatewayApiKey)
        assertEquals(telegram, now.telegramBotToken)
        assertEquals(provider, now.cloudProviderProfiles.single().apiKey)
        assertEquals("http://pc.example.ts.net:8642", now.remoteGatewayUrl)

        // At rest they are sealed with this install's new key, not left as plain text.
        val stored = File(context.filesDir, "datastore/hermes_settings.preferences_pb").readBytes()
        val storedText = String(stored, Charsets.ISO_8859_1)
        assertFalse("credentials must not rest as plain text", storedText.contains(cloud) || storedText.contains(gateway))
        assertTrue(storedText.contains("enc:v1:"))

        assertEquals("""{"token":"$connectorSecret"}""", db.connectorDao().observeAll().first().single().configJson)
        assertEquals("Job", db.scheduledTaskDao().observeAll().first().single().label)
        assertEquals(nodeKey, File(context.filesDir, "tailnet/tailscaled.state").readText())
        assertTrue(context.getSharedPreferences("tailnet", Context.MODE_PRIVATE).getBoolean("autostart", false))
    }
}
