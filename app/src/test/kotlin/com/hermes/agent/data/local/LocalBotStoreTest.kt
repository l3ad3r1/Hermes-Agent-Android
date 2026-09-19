package com.hermes.agent.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.data.remote.ChiefOfBots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalBotStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store() = LocalBotStore(context)

    // ── the Chief's name ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the Chief starts with its default name`() {
        assertEquals("Chief of Bots", store().chiefName.value)
    }

    @Test
    fun `a name survives a restart`() {
        store().setChiefName("Jarvis")

        assertEquals("Jarvis", store().chiefName.value)
    }

    @Test
    fun `a blank name puts the default back`() {
        val store = store()
        store.setChiefName("Jarvis")

        store.setChiefName("   ")

        assertEquals("Chief of Bots", store.chiefName.value)
        assertEquals("Chief of Bots", store().chiefName.value)
    }

    @Test
    fun `the name is tidied before it is kept`() {
        val store = store()

        store.setChiefName("  Jar\nvis  ")

        assertEquals("Jar vis", store.chiefName.value)
    }

    @Test
    fun `the on-device side answers to whatever the Chief is called`() {
        val store = store()
        store.setChiefName("Jarvis")

        val persona = store.systemPromptFor(ChiefOfBots.THREAD)

        assertNotNull(persona)
        assertTrue(persona!!.startsWith("You are Jarvis"))
        // Renamed later, the very next turn uses the new name.
        store.setChiefName("Nova")
        assertTrue(store.systemPromptFor(ChiefOfBots.THREAD)!!.startsWith("You are Nova"))
    }

    // ── the Chief's thread is a persona chat that may manage bots ────────────────────────────

    @Test
    fun `the Chief's shared thread may manage bots and gets no other tools`() {
        val store = store()

        assertTrue(store.isChiefOfBots(ChiefOfBots.THREAD))
        // isLocalBot is what strips a persona chat of the full tool catalogue.
        assertTrue(store.isLocalBot(ChiefOfBots.THREAD))
    }

    @Test
    fun `an ordinary conversation is neither`() {
        val store = store()

        assertFalse(store.isChiefOfBots("some-other-chat"))
        assertFalse(store.isLocalBot("some-other-chat"))
        assertNull(store.systemPromptFor("some-other-chat"))
    }

    @Test
    fun `a local bot is a persona chat but only a granted one may manage bots`() {
        val store = store()
        val plain = store.add("Poet", "You write verse.")!!
        val manager = store.add("Manager", "You manage bots.", isChiefOfBots = true)!!

        assertTrue(store.isLocalBot(plain.id))
        assertFalse(store.isChiefOfBots(plain.id))
        assertTrue(store.isChiefOfBots(manager.id))
        assertEquals("You write verse.", store.systemPromptFor(plain.id))
    }

    @Test
    fun `a later thread of a bot is still that bot's chat, with its persona and powers`() {
        val store = store()
        val plain = store.add("Poet", "You write verse.")!!
        val manager = store.add("Manager", "You manage bots.", isChiefOfBots = true)!!
        val chiefLater = BotThreads.newId(ChiefOfBots.THREAD)
        val plainLater = BotThreads.newId(plain.id)

        assertTrue(store.isLocalBot(chiefLater))
        assertTrue(store.isChiefOfBots(chiefLater))
        assertTrue(store.systemPromptFor(chiefLater)!!.startsWith("You are the user's Chief of Bots"))
        assertTrue(store.isLocalBot(plainLater))
        assertFalse(store.isChiefOfBots(plainLater))
        assertEquals("You write verse.", store.systemPromptFor(plainLater))
        assertTrue(store.isChiefOfBots(BotThreads.newId(manager.id)))
        assertFalse(store.isLocalBot(BotThreads.newId("some-other-chat")))
    }

    // ── data saved before the rename ─────────────────────────────────────────────────────────

    @Test
    fun `a bot saved under the old key keeps its power`() {
        context.getSharedPreferences("local_bots", Context.MODE_PRIVATE).edit()
            .putString(
                "bots",
                """[{"id":"localbot_1","name":"Old","systemPrompt":"p","createdAt":1,"isChiefOfStaff":true}]""",
            )
            .commit()

        val store = store()

        assertTrue(store.bots.value.single().isChiefOfBots)
        assertTrue(store.isChiefOfBots("localbot_1"))
    }

    @Test
    fun `it is saved under the new name from then on`() {
        val store = store()
        store.add("Manager", "You manage bots.", isChiefOfBots = true)

        val raw = context.getSharedPreferences("local_bots", Context.MODE_PRIVATE).getString("bots", "")!!

        assertTrue(raw.contains("isChiefOfBots"))
        assertFalse(raw.contains("isChiefOfStaff"))
        assertTrue(store().bots.value.single().isChiefOfBots)
    }
}
