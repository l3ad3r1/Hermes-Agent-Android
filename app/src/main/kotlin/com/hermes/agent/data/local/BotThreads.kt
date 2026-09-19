package com.hermes.agent.data.local

import com.hermes.agent.util.IdGenerator

/**
 * A bot can have several chat threads, like the main chat. The first is stored under the bot's own
 * conversation id, so every chat that existed before threads did is still its first thread; each
 * later one is that id, [SEPARATOR], and a fresh id. Anything that has to recognise "a chat with
 * this bot" therefore compares [baseOf] the conversation id, not the id itself.
 */
object BotThreads {
    const val SEPARATOR = "#"

    /** The bot's own conversation id, whichever of its threads [conversationId] is. */
    fun baseOf(conversationId: String): String = conversationId.substringBefore(SEPARATOR)

    /** A new thread of the bot whose first thread is [base]. */
    fun newId(base: String): String = "$base$SEPARATOR${IdGenerator.newId()}"

    /** Whether [conversationId] is [base] or one of its later threads. */
    fun belongsTo(conversationId: String, base: String): Boolean = baseOf(conversationId) == base

    /** Whether [conversationId] is a later thread, not the bot's first. */
    fun isLater(conversationId: String): Boolean = conversationId.contains(SEPARATOR)
}
