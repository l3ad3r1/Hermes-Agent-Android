package com.hermes.agent.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotThreadsTest {

    @Test
    fun `a bot's first thread is its own id, so chats from before threads are still its first`() {
        assertEquals("chief_of_bots", BotThreads.baseOf("chief_of_bots"))
        assertFalse(BotThreads.isLater("chief_of_bots"))
    }

    @Test
    fun `a later thread is the bot's id and a fresh one`() {
        val a = BotThreads.newId("localbot_1")
        val b = BotThreads.newId("localbot_1")

        assertTrue(a.startsWith("localbot_1#"))
        assertNotEquals(a, b)
        assertEquals("localbot_1", BotThreads.baseOf(a))
        assertTrue(BotThreads.isLater(a))
    }

    @Test
    fun `threads belong to their own bot only`() {
        assertTrue(BotThreads.belongsTo("desktopbot_reddit", "desktopbot_reddit"))
        assertTrue(BotThreads.belongsTo("desktopbot_reddit#x1", "desktopbot_reddit"))
        assertFalse(BotThreads.belongsTo("desktopbot_reddit2#x1", "desktopbot_reddit"))
        assertFalse(BotThreads.belongsTo("desktopbot_reddit", "desktopbot_reddit#x1"))
    }
}
