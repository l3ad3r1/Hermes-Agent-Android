package com.hermes.agent.data.agent

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicPhoneCommandRouterTest {
    private val router = DeterministicPhoneCommandRouter()

    @Test
    fun `parses timer without model inference`() {
        val command = router.match("Set a timer for 15 minutes") ?: error("not matched")
        assertEquals("alarm", command.call.name)
        assertEquals("set_timer", (command.call.arguments["action"] as JsonPrimitive).content)
        assertEquals(900, (command.call.arguments["duration_seconds"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `converts pm alarm to 24 hour time`() {
        val command = router.match("Set an alarm for 3:05 PM") ?: error("not matched")
        assertEquals(15, (command.call.arguments["hour"] as JsonPrimitive).content.toInt())
        assertEquals(5, (command.call.arguments["minute"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `preserves navigation destination text`() {
        val command = router.match("Navigate to Chandigarh Airport") ?: error("not matched")
        assertEquals("navigation", command.call.name)
        assertEquals("Chandigarh Airport", (command.call.arguments["query"] as JsonPrimitive).content)
    }

    @Test
    fun `matches direct flashlight and media actions`() {
        val flashlight = router.match("Turn on the torch") ?: error("not matched")
        assertEquals("device_control", flashlight.call.name)
        assertTrue((flashlight.call.arguments["enabled"] as JsonPrimitive).content.toBoolean())

        val media = router.match("Next song") ?: error("not matched")
        assertEquals("media_control", media.call.name)
        assertEquals("next", (media.call.arguments["action"] as JsonPrimitive).content)
    }

    @Test
    fun `does not claim ambiguous conversation or calendar requests`() {
        assertNull(router.match("Can you help me choose some music?"))
        assertNull(router.match("Create a meeting tomorrow at 3 PM"))
    }
}
