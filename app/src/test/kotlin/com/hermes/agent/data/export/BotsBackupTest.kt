package com.hermes.agent.data.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.data.local.LocalBotStore
import com.hermes.agent.data.remote.BotProfileStore
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BotsBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun settings(url: String = "", enabled: Boolean = false): SettingsRepository =
        mockk<SettingsRepository>(relaxed = true).also {
            coEvery { it.current() } returns UserSettings(remoteGatewayUrl = url, remoteGatewayEnabled = enabled)
        }

    private fun backup(repo: SettingsRepository = settings()) =
        BotsBackup(LocalBotStore(context), BotProfileStore(context), repo)

    /** What a brand new install looks like: nothing in the stores this backup reads. */
    private fun wipe() {
        listOf("local_bots", "bot_profiles").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun `a full setup comes back on a fresh install with the same bot ids`() = runTest {
        val bots = LocalBotStore(context)
        val scribe = bots.add("Scribe", "Takes meeting notes")!!
        bots.setChiefName("Jarvis")
        BotProfileStore(context).add("redditbot")
        val text = backup(settings("http://pc.ts.net:8642", enabled = true)).export()

        wipe()
        val target = settings()
        val restored = backup(target)
        val report = restored.restore(text, overwrite = false)

        val after = LocalBotStore(context)
        assertEquals(listOf(scribe), after.bots.value)
        assertEquals("Jarvis", after.chiefName.value)
        assertEquals(listOf("default", "redditbot"), BotProfileStore(context).profiles.value)
        coVerify { target.setRemoteGatewayUrl("http://pc.ts.net:8642") }
        coVerify { target.setRemoteGatewayEnabled(true) }
        assertEquals(4, report.added)
    }

    @Test
    fun `the id survives so a restored chat still finds its bot`() = runTest {
        val id = LocalBotStore(context).add("Scribe", "p")!!.id
        val text = backup().export()
        wipe()
        backup().restore(text, overwrite = false)

        assertTrue(LocalBotStore(context).isLocalBot(id))
    }

    @Test
    fun `restoring keeps a name and a gateway that were set on this install`() = runTest {
        val text = run {
            LocalBotStore(context).setChiefName("From file")
            backup(settings("http://old-pc:8642")).export()
        }
        wipe()
        LocalBotStore(context).setChiefName("Mine")
        val target = settings("http://new-pc:8642")

        backup(target).restore(text, overwrite = false)

        assertEquals("Mine", LocalBotStore(context).chiefName.value)
        coVerify(exactly = 0) { target.setRemoteGatewayUrl(any()) }
    }

    @Test
    fun `overwrite lets the file win`() = runTest {
        val text = run {
            LocalBotStore(context).setChiefName("From file")
            backup(settings("http://old-pc:8642")).export()
        }
        wipe()
        LocalBotStore(context).setChiefName("Mine")
        val target = settings("http://new-pc:8642")

        backup(target).restore(text, overwrite = true)

        assertEquals("From file", LocalBotStore(context).chiefName.value)
        coVerify { target.setRemoteGatewayUrl("http://old-pc:8642") }
    }

    @Test
    fun `an existing bot is skipped, and one that would reuse a name is too`() = runTest {
        val bots = LocalBotStore(context)
        bots.add("Scribe", "p")
        val text = backup().export()
        wipe()
        val here = LocalBotStore(context)
        val clash = here.add("Scribe", "different")!!

        val report = backup().restore(text, overwrite = false)

        assertEquals(1, report.skipped)
        assertEquals(listOf(clash), LocalBotStore(context).bots.value)
    }

    @Test
    fun `the default chief name and default profile add nothing`() = runTest {
        val text = backup().export()
        wipe()
        val report = backup().restore(text, overwrite = false)

        assertEquals(0, report.added)
        assertFalse(LocalBotStore(context).bots.value.isNotEmpty())
        assertEquals(listOf("default"), BotProfileStore(context).profiles.value)
    }

    @Test
    fun `an unknown field from a newer build is ignored`() = runTest {
        val text = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"chiefName":"Boss","somethingNew":{"x":1}}""",
        )
        backup().restore(text, overwrite = false)

        assertEquals("Boss", LocalBotStore(context).chiefName.value)
    }
}
