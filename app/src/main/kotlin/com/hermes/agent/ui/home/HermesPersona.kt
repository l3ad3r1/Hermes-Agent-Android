package com.hermes.agent.ui.home

import kotlin.math.absoluteValue

/**
 * Hermes's context-aware persona for the home screen — pure logic, no
 * Android dependencies, so every branch is unit-testable.
 *
 * Composes what Hermes "feels" right now from three signals:
 *  - the user's name, if Hermes has learned it (memory / user model),
 *  - the time of day,
 *  - whether the background agent is currently working a Kanban ticket.
 *
 * The output [Presence] drives both the greeting text and the
 * [com.hermes.agent.ui.components.ExpressiveEyes] mood (inspired by the
 * Xiaozhi ESP32 desk robot's expressive-eye face).
 */
object HermesPersona {

    /** Eye expression states, in the spirit of the Xiaozhi desk robot. */
    enum class Mood { HAPPY, NEUTRAL, FOCUSED, SLEEPY, THINKING, SURPRISED, LISTENING, CELEBRATE }

    data class Presence(
        val greeting: String,
        val statusLine: String,
        val mood: Mood,
    )

    /**
     * @param name the user's name or null if unknown
     * @param hourOfDay 0-23 local hour
     * @param busyTask title of the ticket the background agent is working, or null
     * @param isThinking true while an orchestrator run is composing a reply
     * @param isListening true while the mic is capturing voice input
     * @param seed stabiliser for the personality-line pick (pass e.g.
     *   dayOfYear*24+hour so the line doesn't change on every recomposition
     *   but does rotate over time)
     *
     * Priority: listening (mic hot, most immediate) > busy ticket (FOCUSED) >
     * thinking > time mood. Transient reactions ([pokeReaction],
     * [celebrateReaction]) are layered on top by the ViewModel.
     */
    fun compose(
        name: String?,
        hourOfDay: Int,
        busyTask: String?,
        isThinking: Boolean = false,
        isListening: Boolean = false,
        seed: Long = 0L,
    ): Presence {
        val bucket = bucketFor(hourOfDay)
        val greeting = buildString {
            append(bucket.salutation)
            if (!name.isNullOrBlank()) append(", ").append(name.trim())
        }

        // Listening wins: the user is talking to Hermes right now.
        if (isListening) {
            return Presence(greeting = greeting, statusLine = "I'm listening…", mood = Mood.LISTENING)
        }

        // Busy: focused eyes + an honest "I'm on it" line.
        if (!busyTask.isNullOrBlank()) {
            val shortTask = busyTask.trim().let { if (it.length > 48) it.take(45) + "…" else it }
            return Presence(
                greeting = greeting,
                statusLine = "I'm busy working on \"$shortTask\" — ask me anything anyway.",
                mood = Mood.FOCUSED,
            )
        }

        // Actively composing a reply somewhere (chat, delegate, API server).
        if (isThinking) {
            val line = THINKING_LINES[(seed.absoluteValue % THINKING_LINES.size).toInt()]
            return Presence(greeting = greeting, statusLine = line, mood = Mood.THINKING)
        }

        val lines = bucket.idleLines
        val line = lines[(seed.absoluteValue % lines.size).toInt()]
        return Presence(greeting = greeting, statusLine = line, mood = bucket.mood)
    }

    /**
     * Reaction to the user tapping the eyes: startled wide eyes + a quip.
     * The ViewModel shows this for a few seconds, then reverts to [compose].
     * Pass a per-tap [seed] (e.g. the tap count) so repeated pokes rotate
     * through the quips.
     */
    fun pokeReaction(base: Presence, seed: Long): Presence = Presence(
        greeting = base.greeting,
        statusLine = POKE_QUIPS[(seed.absoluteValue % POKE_QUIPS.size).toInt()],
        mood = Mood.SURPRISED,
    )

    /**
     * Reaction to a background Kanban ticket finishing: a happy bounce +
     * a celebratory line naming the task. The ViewModel shows this for a
     * few seconds, then reverts to [compose].
     */
    fun celebrateReaction(base: Presence, taskTitle: String, seed: Long): Presence {
        val shortTask = taskTitle.trim().let { if (it.length > 40) it.take(37) + "…" else it }
        val quip = CELEBRATE_QUIPS[(seed.absoluteValue % CELEBRATE_QUIPS.size).toInt()]
        return Presence(
            greeting = base.greeting,
            statusLine = "$quip Finished \"$shortTask\".",
            mood = Mood.CELEBRATE,
        )
    }

    /**
     * Extract the user's first name from what Hermes has learned.
     *
     * Two sources, in priority order:
     *  1. Explicit name facts in memories ("my name is X", "user's name is X",
     *     "call me X", "name: X").
     *  2. The Honcho-style user-model paragraph, which by prompt convention
     *     starts with the user's name ("Rinu is a software engineer …").
     */
    fun extractName(memories: List<String>, userModel: String?): String? {
        for (m in memories) {
            for (rx in NAME_PATTERNS) {
                val hit = rx.find(m) ?: continue
                val candidate = hit.groupValues.last().trim().trimEnd('.', ',', '!')
                if (isPlausibleName(candidate)) return candidate
            }
        }
        userModel?.let { model ->
            val first = MODEL_LEAD.find(model.trim())?.groupValues?.get(1)
            if (first != null && isPlausibleName(first)) return first
        }
        return null
    }

    private fun isPlausibleName(s: String): Boolean =
        s.length in 2..20 && s[0].isUpperCase() && s.all { it.isLetter() || it == '-' || it == '\'' } &&
            s.lowercase() !in NON_NAMES

    private data class Bucket(val salutation: String, val mood: Mood, val idleLines: List<String>)

    private fun bucketFor(hour: Int): Bucket = when (hour) {
        in 5..11 -> Bucket(
            "Good morning", Mood.HAPPY,
            listOf(
                "Ready when you are — what's first today?",
                "Coffee for you, tokens for me. Let's go.",
                "The board is clear and I'm wide awake.",
            ),
        )
        in 12..16 -> Bucket(
            "Good afternoon", Mood.NEUTRAL,
            listOf(
                "All quiet — want me to take something off your plate?",
                "Standing by. Delegation is my love language.",
                "Midday check-in: nothing on fire.",
            ),
        )
        in 17..21 -> Bucket(
            "Good evening", Mood.NEUTRAL,
            listOf(
                "Winding down? I can keep working while you rest.",
                "Evening — want a recap of today's threads?",
                "Still here, still sharp.",
            ),
        )
        else -> Bucket(
            "Up late", Mood.SLEEPY,
            listOf(
                "I don't sleep, but you probably should.",
                "Night shift engaged. What do you need?",
                "It's quiet… too quiet. What are we building?",
            ),
        )
    }

    private val NAME_PATTERNS = listOf(
        Regex("""(?i)\buser'?s name is\s+([A-Z][\w'-]+)"""),
        Regex("""(?i)\bmy name is\s+([A-Z][\w'-]+)"""),
        Regex("""(?i)\bcall (?:me|them|him|her)\s+([A-Z][\w'-]+)"""),
        Regex("""(?i)^name\s*[:=]\s*([A-Z][\w'-]+)"""),
        Regex("""(?i)\bname is\s+([A-Z][\w'-]+)"""),
    )

    /** "<Name> is a …" lead of the user-model paragraph. */
    private val MODEL_LEAD = Regex("""^([A-Z][\w'-]+)\s+is\b""")

    /** Words that match the name shape but aren't names. */
    private val NON_NAMES = setOf("the", "user", "they", "there", "this", "that", "unknown")

    private val THINKING_LINES = listOf(
        "Thinking… give me a second.",
        "Composing a reply — neurons at work.",
        "Hold on, connecting some dots…",
    )

    private val POKE_QUIPS = listOf(
        "Hey! I felt that.",
        "Careful — those are load-bearing eyes.",
        "Blinking is my cardio. What's up?",
        "You rang?",
        "Poke registered. Deploying attention.",
    )

    private val CELEBRATE_QUIPS = listOf(
        "Done! ✨",
        "Nailed it. 🎉",
        "One more off the board!",
        "Ta-da —",
        "Shipped it. 🚀",
    )
}
