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
    fun wakeWordMatchingAndRouting_resolvesTargetAgent() {
        val triggers = listOf("Hey Hermes", "Take Note")
        val rules = mapOf("take note" to "productivity")

        val matched = WakeWordConfig.matchTrigger("hey hermes what time is it", triggers)
        assertEquals("Hey Hermes", matched)

        val matched2 = WakeWordConfig.matchTrigger("please take note of this", triggers)
        assertEquals("Take Note", matched2)

        val targetAgent1 = WakeWordConfig.resolveTargetAgent(matched!!, rules, "conversational")
        assertEquals("conversational", targetAgent1)

        val targetAgent2 = WakeWordConfig.resolveTargetAgent(matched2!!, rules, "conversational")
        assertEquals("productivity", targetAgent2)
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
    fun `evaluate matches a trigger phrase inside a transcript hypothesis and routes it`() {
        val triggers = listOf("Hey Hermes", "Take Note")
        val rules = mapOf("take note" to "productivity")

        // The recogniser returns several ranked hypotheses; the second one carries the trigger.
        val hypotheses = listOf("hen hermès", "hey hermes what's the weather", "hey her mess")
        val match = WakeWordService.evaluate(hypotheses, triggers, rules)
        assertEquals("Hey Hermes" to "conversational", match)

        val routed = WakeWordService.evaluate(listOf("please take note of this"), triggers, rules)
        assertEquals("Take Note" to "productivity", routed)
    }

    @Test
    fun `evaluate returns null when no hypothesis contains a trigger`() {
        val triggers = listOf("Hey Hermes")
        assertNull(WakeWordService.evaluate(listOf("start listening now", "what time is it"), triggers, emptyMap()))
        assertNull(WakeWordService.evaluate(emptyList(), triggers, emptyMap()))
    }
}
