package com.hermes.agent.data.remote

import com.hermes.agent.data.remote.ChiefOfBots.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChiefOfBotsTest {

    private val charter = ChiefOfBots.charter("Chief of Bots")

    // ── identity ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the Chief is the gateway's default bot`() {
        // The id is the gateway's own name for its primary agent; renaming it would orphan the
        // session and the jobs.
        assertEquals(BotProfileStore.DEFAULT, ChiefOfBots.PROFILE)
        assertEquals("Chief of Bots", ChiefOfBots.DEFAULT_NAME)
    }

    @Test
    fun `its shared thread cannot collide with any other bot's`() {
        assertFalse(ChiefOfBots.THREAD.startsWith("localbot_"))
        assertFalse(ChiefOfBots.THREAD.startsWith("desktopbot_"))
    }

    // ── the name ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a name is tidied into one short line`() {
        assertEquals("Jarvis", ChiefOfBots.cleanName("  Jarvis  "))
        assertEquals("Jar vis", ChiefOfBots.cleanName("Jar\nvis"))
        assertEquals("Jar vis", ChiefOfBots.cleanName("Jar     vis"))
        assertEquals("Jarvis", ChiefOfBots.cleanName("Ja\u0007rvis"))
    }

    @Test
    fun `a blank name goes back to the default`() {
        assertEquals("Chief of Bots", ChiefOfBots.cleanName(""))
        assertEquals("Chief of Bots", ChiefOfBots.cleanName("   \n "))
        assertEquals("Chief of Bots", ChiefOfBots.cleanName(null))
    }

    @Test
    fun `a long name is cut, not left to run through a prompt`() {
        val cleaned = ChiefOfBots.cleanName("x".repeat(200))

        assertEquals(ChiefOfBots.MAX_NAME_LENGTH, cleaned.length)
    }

    // ── what it is told ──────────────────────────────────────────────────────────────────────

    @Test
    fun `it is told its name and to answer to it, on the PC and on the phone`() {
        val pc = ChiefOfBots.charter("Jarvis")
        val phone = ChiefOfBots.localPersona("Jarvis")

        assertTrue(pc.startsWith("You are Jarvis, the user's Chief of Bots. Answer to Jarvis"))
        assertTrue(phone.startsWith("You are Jarvis, the user's Chief of Bots. Answer to Jarvis"))
    }

    @Test
    fun `under its default name it is not told it is itself twice over`() {
        val pc = ChiefOfBots.charter("Chief of Bots")
        val phone = ChiefOfBots.localPersona("Chief of Bots")

        assertTrue(pc.startsWith("You are the user's Chief of Bots."))
        assertTrue(phone.startsWith("You are the user's Chief of Bots."))
        assertFalse(pc.contains("Chief of Bots, the user's Chief of Bots"))
        assertFalse(phone.contains("Chief of Bots, the user's Chief of Bots"))
    }

    @Test
    fun `nothing still calls it the old name`() {
        assertFalse(charter.contains("Staff"))
        assertFalse(ChiefOfBots.localPersona("Chief of Bots").contains("Staff"))
    }

    @Test
    fun `the charter covers each area the role was given`() {
        val covered = mapOf(
            "operations layer" to "the role itself",
            "Triage the inbox" to "email triage",
            "Manage the calendar" to "calendar",
            "reminders" to "reminders and watches",
            "open loops" to "follow-ups",
            "Research the web" to "research",
            "own voice" to "writing in the user's voice",
            "Google Drive" to "Drive",
            "GitHub and Vercel" to "code and deploys",
            "with their approval" to "working on the computer",
            "browser work, downloads and screenshots" to "the phone",
            "Talk to the other bots" to "coordination",
            "Batch decisions" to "batching approvals",
            "Escalate only" to "escalation",
        )
        covered.forEach { (phrase, area) ->
            assertTrue("charter is missing $area (\"$phrase\")", charter.contains(phrase))
        }
    }

    @Test
    fun `email is sent only when the user asks`() {
        assertTrue(charter.contains("Send only when the user tells you to send"))
        assertTrue(charter.contains("Never send, post, pay, delete or deploy unless the user asked"))
    }

    @Test
    fun `it manages bots on the computer and leaves the phone's to the app`() {
        assertTrue(charter.contains("hermes profile create"))
        assertTrue(charter.contains("Deleting a bot needs the user's explicit yes"))
        // Told so, because the PC cannot create a bot on the phone.
        assertTrue(charter.contains("Bots on their phone are created and removed by the app itself"))
    }

    @Test
    fun `it is told to admit what it cannot do rather than describe it`() {
        assertTrue(charter.contains("If you do not have a tool for something, say so plainly"))
        assertTrue(charter.contains("Never describe work you did not do"))
    }

    @Test
    fun `the on-device side is given the one tool and told the big jobs are the PC's`() {
        val persona = ChiefOfBots.localPersona("Chief of Bots")

        assertTrue(persona.contains("manage_bots"))
        assertTrue(persona.contains("run on the user's PC"))
        assertTrue(persona.contains("Only act when the user asks"))
    }

    @Test
    fun `the charter is plain text with no leftover indentation`() {
        assertTrue(charter.lines().none { it.startsWith("        ") })
    }

    // ── which brain answers ──────────────────────────────────────────────────────────────────

    private fun route(text: String, attachment: Boolean = false, pcOnline: Boolean = true) =
        ChiefOfBots.route(text, attachment, pcOnline)

    @Test
    fun `ordinary work goes to the PC while it is up`() {
        listOf(
            "clear my inbox",
            "draft a reply to Sam",
            "what's on my calendar tomorrow?",
            "summarise this thread and tell me what to do",
            "find the Q3 deck in Drive",
            "remind me to call the bank at 4",
        ).forEach { assertEquals(it, Route.PC, route(it)) }
    }

    @Test
    fun `asking for a bot to create, remove or list bots goes to the phone`() {
        listOf(
            "Create a bot named Scribe that takes meeting notes",
            "create a bot",
            "make a new bot for my reading list",
            "make me a bot that tracks my spending",
            "add another bot for reminders",
            "set up a bot to watch the news",
            "I'd like you to build a helpful bot called Poet",
            "remove the bot named scribe",
            "delete the poet bot",
            "rename my bot",
            "list all my bots",
            "show me my bots",
            "what bots do I have?",
            "which bots exist",
            "how many bots do I have",
            "manage my bots",
        ).forEach { assertEquals(it, Route.PHONE, route(it)) }
    }

    @Test
    fun `merely mentioning a bot is still work for the PC`() {
        listOf(
            "ask redditbot to draft the 9am post",
            "make sure the bot posts at nine",
            "make the bot post now",
            "show me what the reddit bot posted",
            "add a reminder to tell the bot to stop",
            "what are the bots doing right now?",
            "delete the old drafts from the drafts folder",
            "summarise the bot logs",
        ).forEach { assertEquals(it, Route.PC, route(it)) }
    }

    @Test
    fun `only asking to see the bots is answered by the app itself`() {
        listOf(
            "list all my bots",
            "List all my bots",
            "show me my bots",
            "what bots do I have?",
            "which bots exist",
            "how many bots do I have",
        ).forEach { assertTrue(it, ChiefOfBots.isBotListRequest(it)) }
    }

    @Test
    fun `anything that changes a bot is not treated as a plain listing`() {
        listOf(
            "create a bot named Scribe",
            "remove the bot named scribe",
            "list my bots and remove the poet bot",
            "show me my bots, then delete the old bot",
            "manage my bots",
            "rename my bot",
        ).forEach { assertFalse(it, ChiefOfBots.isBotListRequest(it)) }
    }

    @Test
    fun `mentioning a bot is not a listing request either`() {
        listOf(
            "ask redditbot to draft the 9am post",
            "show me what the reddit bot posted",
            "what are the bots doing right now?",
            "clear my inbox",
        ).forEach { assertFalse(it, ChiefOfBots.isBotListRequest(it)) }
    }

    // ── requests the app carries out itself ──────────────────────────────────────────────────

    private fun parse(text: String) = ChiefOfBots.parseBotCommand(text)

    @Test
    fun `a clear create request is read with its name and what it is for`() {
        assertEquals(
            ChiefOfBots.BotCommand.Create("Scribe", "takes meeting notes"),
            parse("Create a bot named Scribe that takes meeting notes"),
        )
        assertEquals(
            ChiefOfBots.BotCommand.Create("Poet", "writes verse"),
            parse("make me a bot called Poet who writes verse"),
        )
        assertEquals(
            ChiefOfBots.BotCommand.Create("Daily Digest", "morning news"),
            parse("add another bot called Daily Digest for morning news"),
        )
        assertEquals(ChiefOfBots.BotCommand.Create("Nova", null), parse("set up a helpful bot named Nova"))
        assertEquals(ChiefOfBots.BotCommand.Create("Scribe", null), parse("Create a bot named 'Scribe'."))
        assertEquals(ChiefOfBots.BotCommand.Create("scribe", null), parse("create a new bot named scribe!"))
    }

    @Test
    fun `a clear remove request is read by name`() {
        assertEquals(ChiefOfBots.BotCommand.Remove("scribe"), parse("Remove the bot named scribe"))
        assertEquals(ChiefOfBots.BotCommand.Remove("Poet"), parse("delete the bot called Poet."))
        assertEquals(ChiefOfBots.BotCommand.Remove("scribe"), parse("delete bot scribe"))
        assertEquals(ChiefOfBots.BotCommand.Remove("poet"), parse("delete the poet bot"))
        assertEquals(ChiefOfBots.BotCommand.Remove("scribe"), parse("get rid of my scribe bot"))
    }

    @Test
    fun `anything looser or compound is left to the model`() {
        listOf(
            "create a bot",
            "create a bot for reminders",
            "make a new bot",
            "remove a bot",
            "remove the bot",
            "delete the bot",
            "create a bot named Scribe and remove the bot named Poet",
            "remove the bot named scribe and list my bots",
            "ask redditbot to draft the 9am post",
            "make sure the bot posts at nine",
            "list all my bots",
            "clear my inbox",
        ).forEach { assertEquals(it, null, parse(it)) }
    }

    // ── finding the desktop's bots ───────────────────────────────────────────────────────────

    @Test
    fun `asking to look for the PC's bots is recognised, and goes to the phone`() {
        listOf(
            "find the bots on my PC",
            "Check my desktop for bots",
            "look for bots on the desktop",
            "discover bots on my computer",
            "import the desktop bots",
            "add the desktop's bots",
        ).forEach {
            assertTrue(it, ChiefOfBots.isDiscoverRequest(it))
            assertEquals(it, Route.PHONE, route(it))
        }
    }

    @Test
    fun `other talk of bots or of the PC is not a search`() {
        listOf(
            "list all my bots",
            "clear my inbox on the desktop",
            "ask redditbot to draft the 9am post",
            "make a bot for my pc backups",
        ).forEach { assertFalse(it, ChiefOfBots.isDiscoverRequest(it)) }
    }

    private val offered = listOf("redditbot", "coder")

    @Test
    fun `a yes to the offer adds everything offered`() {
        listOf("yes", "Yes!", "yeah", "sure", "ok", "please do", "go ahead", "add them", "yes please")
            .forEach { assertEquals(it, ChiefOfBots.OfferReply.All, ChiefOfBots.parseOfferReply(it, offered)) }
    }

    @Test
    fun `a no to the offer adds nothing`() {
        listOf("no", "No thanks", "not now", "skip", "later", "nope.")
            .forEach { assertEquals(it, ChiefOfBots.OfferReply.None, ChiefOfBots.parseOfferReply(it, offered)) }
    }

    @Test
    fun `naming some of the bots adds just those`() {
        assertEquals(ChiefOfBots.OfferReply.Some(listOf("redditbot")), ChiefOfBots.parseOfferReply("add redditbot", offered))
        assertEquals(ChiefOfBots.OfferReply.Some(listOf("coder")), ChiefOfBots.parseOfferReply("just the coder", offered))
        assertEquals(
            ChiefOfBots.OfferReply.Some(listOf("redditbot", "coder")),
            ChiefOfBots.parseOfferReply("yes, add coder and RedditBot", offered),
        )
    }

    @Test
    fun `anything else is not an answer, so the offer stays open`() {
        assertEquals(null, ChiefOfBots.parseOfferReply("what does redditbot do?", offered))
        assertEquals(null, ChiefOfBots.parseOfferReply("clear my inbox", offered))
        assertEquals(null, ChiefOfBots.parseOfferReply("yes and also draft a reply to Sam about the invoice tomorrow morning", offered))
    }

    @Test
    fun `an attachment goes to the phone because the PC takes text alone`() {
        assertEquals(Route.PHONE, route("what is in this picture?", attachment = true))
        assertEquals(Route.PHONE, route("", attachment = true))
    }

    @Test
    fun `everything goes to the phone when the PC cannot be reached`() {
        assertEquals(Route.PHONE, route("clear my inbox", pcOnline = false))
        assertEquals(Route.PHONE, route("create a bot named Scribe", pcOnline = false))
    }

    @Test
    fun `an attachment or a bot request wins over a PC that is up`() {
        assertEquals(Route.PHONE, route("draft a reply", attachment = true, pcOnline = true))
        assertEquals(Route.PHONE, route("list my bots", pcOnline = true))
    }
}
