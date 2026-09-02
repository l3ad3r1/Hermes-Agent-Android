package com.hermes.agent.service

import com.hermes.agent.data.settings.WakeWordConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordServiceTest {

    @Test
    fun micBusyState_togglesCorrectly() {
        WakeWordService.setMicBusy(true)
        assertTrue(WakeWordService.isMicBusy.value)

        WakeWordService.setMicBusy(false)
        assertFalse(WakeWordService.isMicBusy.value)
    }

    @Test
    fun `routing resolves the target agent for a matched trigger`() {
        val rules = mapOf("take note" to "productivity")
        assertEquals("conversational", WakeWordConfig.resolveTargetAgent("Hey Hermes", rules, "conversational"))
        assertEquals("productivity", WakeWordConfig.resolveTargetAgent("Take Note", rules, "conversational"))
    }

    @Test
    fun triggerList_capsAndNormalizesProperly() {
        val triggers = mutableListOf("  hey hermes  ")
        for (i in 1..40) {
            triggers.add("trigger $i")
        }
        val normalized = WakeWordConfig.normalizeTriggers(triggers)
        assertEquals(32, normalized.size)
        assertEquals("hey hermes", normalized[0])
    }

    @Test
    fun `evaluate fires only when the utterance begins with a trigger and is short`() {
        val triggers = listOf("Hey Hermes", "Take Note")
        val rules = mapOf("take note" to "productivity")

        // Bare phrase, and phrase + a few words, both wake.
        assertEquals(
            "Hey Hermes" to "conversational",
            WakeWordService.evaluate(listOf("hen hermès", "hey hermes what's the weather"), triggers, rules),
        )
        assertEquals(
            "Take Note" to "productivity",
            WakeWordService.evaluate(listOf("take note"), triggers, rules),
        )
    }

    @Test
    fun `evaluate ignores the trigger buried in ordinary speech`() {
        val triggers = listOf("Hey Hermes", "Take Note")
        // Not at the start.
        assertNull(WakeWordService.evaluate(listOf("so I told hey hermes about the meeting"), triggers, emptyMap()))
        assertNull(WakeWordService.evaluate(listOf("please take note of this for later"), triggers, emptyMap()))
        // At the start but far too long to be a wake phrase.
        assertNull(
            WakeWordService.evaluate(
                listOf("hey hermes remind me to call the plumber first thing tomorrow morning"),
                triggers,
                emptyMap(),
            ),
        )
    }

    @Test
    fun `evaluate returns null on no match or empty input`() {
        val triggers = listOf("Hey Hermes")
        assertNull(WakeWordService.evaluate(listOf("start listening now", "what time is it"), triggers, emptyMap()))
        assertNull(WakeWordService.evaluate(emptyList(), triggers, emptyMap()))
    }
}
