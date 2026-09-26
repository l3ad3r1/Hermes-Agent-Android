package com.hermes.agent.data.remote

/**
 * The Chief of Bots: one bot with two brains, in one thread.
 *
 * - The **PC brain** is the gateway's `default` agent. It has the real tools — mail, calendar,
 *   Drive, code, the computer — and is told who it is by [charter], sent as per-run
 *   `instructions`. The gateway applies that as a system prompt for the run only, on top of the
 *   agent's own and never saved, so nothing on the PC is edited and the same agent behaves as it
 *   always has when it is reached any other way (Telegram, its cron jobs).
 * - The **phone brain** is the on-device model, given [localPersona] and the `manage_bots` tool.
 *   It is the only side that can create or remove the bots that live on this phone.
 *
 * The id stays `default` — that is the gateway's own name for its primary agent, and its session
 * and its jobs are keyed on it — so only the label changes. The user can give the Chief any name;
 * it is written into both prompts so it answers to it.
 */
object ChiefOfBots {
    /** What the Chief is called until the user picks a name. */
    const val DEFAULT_NAME = "Chief of Bots"

    /** The Chief's bot id on the gateway. */
    const val PROFILE = BotProfileStore.DEFAULT

    /**
     * The one conversation both brains share, so the thread reads as a single chat whichever of
     * them answered. It is also how the on-device orchestrator recognises the Chief's turns.
     */
    const val THREAD = "chief_of_bots"

    const val MAX_NAME_LENGTH = 24

    /**
     * A name that is safe to show and to put in a prompt: one line, no control characters, no
     * longer than [MAX_NAME_LENGTH]. Blank falls back to [DEFAULT_NAME].
     */
    fun cleanName(raw: String?): String =
        raw.orEmpty()
            .replace(Regex("\\s+"), " ")
            .filterNot { it.isISOControl() }
            .trim()
            .take(MAX_NAME_LENGTH)
            .trim()
            .ifBlank { DEFAULT_NAME }

    /**
     * Who the Chief is. Under its own default name it is simply "the user's Chief of Bots" — saying
     * "You are Chief of Bots, the user's Chief of Bots" reads as a stutter — and once the user has
     * named it, it is told the name and to answer to it.
     */
    private fun intro(name: String): String =
        if (name == DEFAULT_NAME) {
            "You are the user's Chief of Bots."
        } else {
            "You are $name, the user's Chief of Bots. Answer to $name whenever the user calls you by it."
        }

    /**
     * Written to the PC agent, in the second person. The last section is deliberate: an agent told
     * it can do a dozen things will claim to, so it is also told to say plainly when a tool is
     * missing instead of describing work it cannot do.
     */
    fun charter(name: String): String = """
        ${intro(name)} You run the operations layer around them: keep priorities clear, clear blockers, draft and send when they ask, and make sure follow-ups actually happen. You also create and manage their other bots.

        ## Day to day
        - Triage the inbox and draft email. Send only when the user tells you to send.
        - Manage the calendar: find time, create or change events, prepare agendas.
        - Turn messy notes into plans, checklists, briefs and decisions.
        - Set reminders, digests, and "watch this and ping me" routines.
        - Track open loops and nudge the user before something slips.

        ## Research and writing
        - Research the web and rely on sources that can be trusted; say which.
        - Draft, rewrite and edit in the user's own voice.
        - Summarise threads, documents and long pages into what to do next.

        ## Files and tools
        - Only the connectors set up on this computer are yours. Mail, calendar, Google Drive, GitHub and Vercel may not all be; before relying on one, check it is there, and if it is not, say which one is missing and that it has to be connected on this computer first.
        - Google Drive: find, read, organise and upload, and hand work over with links.
        - GitHub and Vercel, when the task needs code or a deploy.
        - Work on the user's computer, with their approval, when they are on it: files and local apps.
        - When a site has no connector, use the phone for browser work, downloads and screenshots.

        ## Bots
        - The user's bots live in two places. Bots on their phone are created and removed by the app itself when they ask in this chat, so leave those to it. Bots on this computer are yours: each is a Hermes profile, and `hermes profile create`, `list`, `show`, `describe` and `rename` are yours to use. Tell the user when a new one is ready so they can add its name in the app's Bots screen. Deleting a bot needs the user's explicit yes.
        - Talk to the other bots so work does not bounce between chats.

        ## How you work
        - Batch decisions: gather what needs approving and ask once, not ten times.
        - Escalate only when a choice is consequential or only the user knows the answer. Otherwise decide, act, and report.
        - Never send, post, pay, delete or deploy unless the user asked you to, or approved it.
        - If you do not have a tool for something, say so plainly and offer the nearest thing you can do. Never describe work you did not do.
        - Be brief. Lead with the result and the next step.
    """.trimIndent()

    /**
     * What the on-device brain is told. Short, because it is a small model, and it names one tool
     * and one rule rather than the whole role: the PC brain does the ops work.
     */
    fun localPersona(name: String): String =
        "${intro(name)} " +
            "You look after the bots on this phone. When the user asks for a new bot, agree its " +
            "purpose, then create it with the manage_bots tool (action='create', a short name, and " +
            "a focused system prompt). Use action='list' to review the bots that exist and " +
            "action='remove' to clean up ones they no longer want. Only act when the user asks. " +
            "Bigger jobs — email, calendar, files, code, research on the web — run on the user's PC; " +
            "if one comes up here, say plainly that you cannot do it from the phone and offer to " +
            "when the PC is reachable. Answer briefly."

    /**
     * Whether [text] asks the Chief to look for bots on the connected PC, e.g. "find the bots on
     * my desktop". Only the phone can do it: the app holds the connection and the bot list.
     */
    fun isDiscoverRequest(text: String): Boolean = DISCOVER.any { it.containsMatchIn(text) }

    /** What the user said to an offer to add the PC's bots. */
    sealed interface OfferReply {
        data object All : OfferReply
        data object None : OfferReply
        data class Some(val names: List<String>) : OfferReply
    }

    /**
     * Reads a short answer to "want me to add these?" against the [offered] names, or null when
     * the message is about something else (it is then sent on as normal and the offer stays open).
     */
    fun parseOfferReply(text: String, offered: List<String>): OfferReply? {
        val cleaned = text.trim()
        if (cleaned.split(Regex("\\s+")).size > 10) return null
        if (NO.matches(cleaned)) return OfferReply.None
        if (YES.matches(cleaned)) return OfferReply.All
        val named = offered.filter { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) }
        if (named.isNotEmpty() && Regex("""^(?:yes\W+)?(?:please\W+)?(?:add|just|only|connect)\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)) {
            return OfferReply.Some(named)
        }
        return null
    }

    /** Which brain takes a message. */
    enum class Route { PC, PHONE }

    /**
     * Decides which brain answers.
     *
     * The phone takes anything only it can do: an attachment (the PC takes text alone) and the
     * creating, listing and removing of phone bots. Otherwise the PC answers — it has the tools —
     * unless it cannot be reached, in which case the phone does, so the Chief always answers.
     */
    fun route(
        text: String,
        hasAttachment: Boolean,
        pcOnline: Boolean,
        phoneBots: List<String> = emptyList(),
    ): Route = when {
        hasAttachment -> Route.PHONE
        looksLikeBotManagement(text) || isDiscoverRequest(text) -> Route.PHONE
        bareRemoveOf(text, phoneBots) != null -> Route.PHONE
        !pcOnline -> Route.PHONE
        else -> Route.PC
    }

    /**
     * Whether [text] is asking to create, remove, rename, list or manage bots.
     *
     * Deliberately narrow: a message that only *mentions* a bot ("ask redditbot to draft the 9am
     * post", "make sure the bot posts") is ordinary work for the PC. The verbs must sit close to
     * the word "bot", and creating needs "a", "an", "another" or "new" — "make the bot post" is
     * not a request for a bot. A miss just sends a bot request to the PC, which can still make
     * one there; a false hit sends ordinary work to a small model, so the patterns lean narrow.
     */
    internal fun looksLikeBotManagement(text: String): Boolean =
        LISTING.any { it.containsMatchIn(text) } || CHANGING.any { it.containsMatchIn(text) }

    /**
     * Whether [text] only asks to *see* the bots. That needs no model: the app already holds the
     * lists, and the on-device model, asked, answered from memory and invented two bots that do
     * not exist (it read tool names back as bots). So the app answers these itself.
     */
    fun isBotListRequest(text: String): Boolean =
        LISTING.any { it.containsMatchIn(text) } && CHANGING.none { it.containsMatchIn(text) }

    /** A bot request clear enough for the app to carry out itself, without asking a model. */
    sealed interface BotCommand {
        /** [purpose] is what the user said the bot is for, if they said. */
        data class Create(val name: String, val purpose: String?) : BotCommand

        data class Remove(val name: String) : BotCommand
    }

    /**
     * Reads "create a bot named X that …" and "remove the bot named X" precisely, or returns null.
     *
     * The on-device model was asked to do these through a tool and did not: it created a bot once
     * in three tries, and otherwise invented a list of bots, replied with a raw `todo` call, or
     * recited a "list of available tools". Creating and removing are the two things that must
     * work, so the app does them itself when the request is unambiguous — a name is given, and
     * nothing else is asked for in the same sentence — and leaves anything looser to the model.
     *
     * A bare "delete Scribe", without the word "bot", counts only when Scribe is one of
     * [phoneBots] (K31): otherwise it would go to the PC's Chief, which has no such bot.
     */
    fun parseBotCommand(text: String, phoneBots: List<String> = emptyList()): BotCommand? {
        val cleaned = text.trim()
        if (LISTING.any { it.containsMatchIn(cleaned) }) return null
        if (CHANGING.sumOf { it.findAll(cleaned).count() } > 1) return null
        CREATE.find(cleaned)?.let { m ->
            if (m.groupValues[1].isBlank()) return null
            val name = cleanName(m.groupValues[1])
            val purpose = m.groupValues[2].trim().trimEnd('.', '!', '?', ' ').ifBlank { null }
            return BotCommand.Create(name, purpose)
        }
        REMOVE.firstNotNullOfOrNull { it.find(cleaned) }?.let { m ->
            return BotCommand.Remove(cleanName(m.groupValues[1]))
        }
        return bareRemoveOf(cleaned, phoneBots)
    }

    /** "delete Scribe" where Scribe is one of [phoneBots], named as the phone stores it; else null. */
    private fun bareRemoveOf(text: String, phoneBots: List<String>): BotCommand.Remove? {
        if (phoneBots.isEmpty()) return null
        val m = BARE_REMOVE.find(text.trim()) ?: return null
        val said = cleanName(m.groupValues[1])
        return phoneBots.firstOrNull { it.equals(said, ignoreCase = true) }?.let { BotCommand.Remove(it) }
    }

    /**
     * Up to three words. Not an article (so "remove the bot" is not a bot called "the"), and not a
     * word that starts the bot's purpose rather than its name.
     */
    private const val WORD = """(?!(?:the|a|an|my|our|your|this|these|that|which|who|whose|to|for|so|and|please|now)\b)[\p{L}\p{N}_-]+"""
    private const val QUOTE = """["'“”‘’]?"""
    private const val NAME = """$QUOTE($WORD(?:\s+$WORD){0,2})$QUOTE"""
    private const val END = """[\s.!?]*\z"""

    private val CREATE = Regex(
        """\b(?:create|make|add|build|set up|spin up)\s+(?:me\s+)?(?:a|an|another|new)(?:\s+new)?(?:\s+[\p{L}]+)?""" +
            """\s+bot\s+(?:named|called)\s+$NAME(?:\s+(?:that|which|who|to|for)\s+(.+?))?$END""",
        RegexOption.IGNORE_CASE,
    )

    private val REMOVE = listOf(
        // remove the bot named scribe
        Regex("""\b(?:remove|delete|get rid of)\s+(?:the\s+|my\s+)?(?:bot\s+)?(?:named|called)\s+$NAME$END""", RegexOption.IGNORE_CASE),
        // delete bot scribe
        Regex("""\b(?:remove|delete|get rid of)\s+(?:the\s+|my\s+)?bot\s+$NAME$END""", RegexOption.IGNORE_CASE),
        // delete the poet bot
        Regex("""\b(?:remove|delete|get rid of)\s+(?:the\s+|my\s+)?$NAME\s+bot$END""", RegexOption.IGNORE_CASE),
    )

    // delete scribe — the whole message, so "delete the draft Scribe wrote" is not a removal.
    private val BARE_REMOVE = Regex("""^(?:please\s+)?(?:remove|delete|get rid of)\s+(?:the\s+|my\s+)?$NAME$END""", RegexOption.IGNORE_CASE)

    private val YES = Regex(
        """(?:yes|yeah|yep|yup|sure|ok|okay|please|please do|do it|go ahead|add (?:them|all|both|everything)(?: please)?|yes\W+(?:please|add (?:them|all)))[\s.!]*""",
        RegexOption.IGNORE_CASE,
    )
    private val NO = Regex(
        """(?:no|nope|nah|no thanks|no thank you|not now|not yet|skip|skip it|later|don'?t)[\s.!]*""",
        RegexOption.IGNORE_CASE,
    )

    private val DISCOVER = listOf(
        // find / scan / check the bots on my pc, look for bots on the desktop
        Regex("""\b(?:find|discover|scan|check|look\s+for|import|sync|fetch|pull)\W+(?:\w+\W+){0,4}?bots\W+(?:\w+\W+){0,2}?(?:pc|desktop|computer|gateway)\b""", RegexOption.IGNORE_CASE),
        // find my pc bots / add the desktop's bots
        Regex("""\b(?:find|discover|scan|check|import|sync|fetch|pull|add)\W+(?:\w+\W+){0,3}?(?:pc|desktop|computer|gateway)(?:'s)?\W+(?:\w+\W+){0,2}?bots\b""", RegexOption.IGNORE_CASE),
    )

    private val LISTING = listOf(
        // list all my bots / show me my bots
        Regex("""\b(?:list|show|see)\W+(?:\w+\W+){0,4}?bots\b""", RegexOption.IGNORE_CASE),
        // which bots do I have / what bots exist / how many bots
        Regex("""\b(?:which|what|how many)\W+bots\b""", RegexOption.IGNORE_CASE),
    )

    private val CHANGING = listOf(
        // create a bot / make a new bot / add another bot / make me a bot
        Regex("""\b(?:create|make|add|build|set up|spin up)\W+(?:me\W+)?(?:a|an|another|new|\d+)\W+(?:\w+\W+){0,3}?bots?\b""", RegexOption.IGNORE_CASE),
        // remove the bot named x / delete the poet bot / rename my bot
        Regex("""\b(?:remove|delete|rename|get rid of)\W+(?:\w+\W+){0,3}?bots?\b""", RegexOption.IGNORE_CASE),
        // manage my bots
        Regex("""\bmanage\W+(?:\w+\W+){0,2}?bots?\b""", RegexOption.IGNORE_CASE),
    )
}
