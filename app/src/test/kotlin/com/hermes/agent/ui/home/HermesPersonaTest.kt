package com.hermes.agent.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesPersonaTest {

    // --- greeting buckets ---

    @Test
    fun `morning greeting with name`() {
        val p = HermesPersona.compose(name = "Ren", hourOfDay = 8, busyTask = null)
        assertEquals("Good morning, Ren", p.greeting)
        assertEquals(HermesPersona.Mood.HAPPY, p.mood)
    }

    @Test
    fun `afternoon greeting without name`() {
        val p = HermesPersona.compose(name = null, hourOfDay = 14, busyTask = null)
        assertEquals("Good afternoon", p.greeting)
        assertEquals(HermesPersona.Mood.NEUTRAL, p.mood)
    }

    @Test
    fun `evening bucket covers 17 to 21`() {
        assertEquals("Good evening", HermesPersona.compose(null, 17, null).greeting)
        assertEquals("Good evening", HermesPersona.compose(null, 21, null).greeting)
    }

    @Test
    fun `late night is sleepy`() {
        val p = HermesPersona.compose(name = "Ren", hourOfDay = 2, busyTask = null)
        assertEquals("Up late, Ren", p.greeting)
        assertEquals(HermesPersona.Mood.SLEEPY, p.mood)
    }

    // --- busy status ---

    @Test
    fun `busy task wins and mood is focused`() {
        val p = HermesPersona.compose("Ren", 9, busyTask = "Wire cron deliveries to Telegram")
        assertEquals(HermesPersona.Mood.FOCUSED, p.mood)
        assertTrue(p.statusLine.contains("Wire cron deliveries to Telegram"))
        assertTrue(p.statusLine.startsWith("I'm busy working on"))
        // Greeting stays time/name aware even while busy.
        assertEquals("Good morning, Ren", p.greeting)
    }

    @Test
    fun `long busy task titles are truncated`() {
        val long = "A".repeat(80)
        val p = HermesPersona.compose(null, 9, busyTask = long)
        assertTrue(p.statusLine.length < 80 + 40)
        assertTrue(p.statusLine.contains("…"))
    }

    @Test
    fun `idle line is stable for a given seed`() {
        val a = HermesPersona.compose(null, 9, null, seed = 123)
        val b = HermesPersona.compose(null, 9, null, seed = 123)
        assertEquals(a.statusLine, b.statusLine)
    }

    // --- thinking ---

    @Test
    fun `thinking mood while a reply is being composed`() {
        val p = HermesPersona.compose("Ren", 9, busyTask = null, isThinking = true)
        assertEquals(HermesPersona.Mood.THINKING, p.mood)
        assertEquals("Good morning, Ren", p.greeting)
        assertTrue(p.statusLine.isNotBlank())
    }

    @Test
    fun `busy ticket outranks thinking`() {
        val p = HermesPersona.compose("Ren", 9, busyTask = "Ship v1", isThinking = true)
        assertEquals(HermesPersona.Mood.FOCUSED, p.mood)
        assertTrue(p.statusLine.contains("Ship v1"))
    }

    // --- listening ---

    @Test
    fun `listening mood while the mic is hot`() {
        val p = HermesPersona.compose("Ren", 14, busyTask = null, isListening = true)
        assertEquals(HermesPersona.Mood.LISTENING, p.mood)
        assertEquals("I'm listening…", p.statusLine)
    }

    @Test
    fun `listening outranks busy and thinking`() {
        val p = HermesPersona.compose(
            "Ren", 14, busyTask = "Ship v1", isThinking = true, isListening = true,
        )
        assertEquals(HermesPersona.Mood.LISTENING, p.mood)
    }

    // --- celebrate reaction ---

    @Test
    fun `celebrate reaction is happy and names the task`() {
        val base = HermesPersona.compose("Ren", 14, null)
        val c = HermesPersona.celebrateReaction(base, taskTitle = "Wire cron to Telegram", seed = 1)
        assertEquals(HermesPersona.Mood.CELEBRATE, c.mood)
        assertEquals(base.greeting, c.greeting)
        assertTrue(c.statusLine.contains("Wire cron to Telegram"))
    }

    @Test
    fun `celebrate truncates long task titles`() {
        val base = HermesPersona.compose(null, 14, null)
        val c = HermesPersona.celebrateReaction(base, taskTitle = "T".repeat(80), seed = 1)
        assertTrue(c.statusLine.contains("…"))
    }

    // --- poke reaction ---

    @Test
    fun `poke reaction is surprised and keeps the greeting`() {
        val base = HermesPersona.compose("Ren", 9, null)
        val poked = HermesPersona.pokeReaction(base, seed = 1)
        assertEquals(HermesPersona.Mood.SURPRISED, poked.mood)
        assertEquals(base.greeting, poked.greeting)
        assertTrue(poked.statusLine.isNotBlank())
    }

    @Test
    fun `poke quips rotate with the seed`() {
        val base = HermesPersona.compose(null, 9, null)
        val a = HermesPersona.pokeReaction(base, seed = 1)
        val b = HermesPersona.pokeReaction(base, seed = 2)
        assertTrue(a.statusLine != b.statusLine)
    }

    // --- name extraction ---

    @Test
    fun `extracts name from explicit memory fact`() {
        val name = HermesPersona.extractName(
            memories = listOf("Enjoys hiking", "User's name is Ren", "Lives in Kochi"),
            userModel = null,
        )
        assertEquals("Ren", name)
    }

    @Test
    fun `extracts name from my-name-is phrasing`() {
        assertEquals(
            "Priya",
            HermesPersona.extractName(listOf("my name is Priya."), null),
        )
    }

    @Test
    fun `extracts name from call-me phrasing`() {
        assertEquals(
            "Dax",
            HermesPersona.extractName(listOf("Prefers that we call him Dax"), null),
        )
    }

    @Test
    fun `falls back to user model lead`() {
        val name = HermesPersona.extractName(
            memories = listOf("Likes coffee"),
            userModel = "Rinu is a software engineer based in India who values efficiency.",
        )
        assertEquals("Rinu", name)
    }

    @Test
    fun `memory fact beats user model lead`() {
        val name = HermesPersona.extractName(
            memories = listOf("User's name is Ren"),
            userModel = "Rinu is a software engineer.",
        )
        assertEquals("Ren", name)
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(HermesPersona.extractName(listOf("Enjoys hiking", "Owns a drone"), null))
        assertNull(HermesPersona.extractName(emptyList(), null))
    }

    @Test
    fun `rejects non-name shapes`() {
        // "The" matches the lead regex shape but is a stop word.
        assertNull(HermesPersona.extractName(emptyList(), "The is a strange sentence."))
        // Lowercase candidates are not names.
        assertNull(HermesPersona.extractName(listOf("my name is ren"), null))
    }
}
