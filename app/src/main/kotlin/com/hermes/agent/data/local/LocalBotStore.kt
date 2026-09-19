package com.hermes.agent.data.local

import android.content.Context
import com.hermes.agent.data.export.ImportReport
import com.hermes.agent.data.remote.ChiefOfBots
import com.hermes.agent.ui.bloub.ColorId
import com.hermes.agent.ui.bloub.ShapeId
import com.hermes.agent.util.IdGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LocalBot(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val createdAt: Long,
    val shape: ShapeId = ShapeId.CERCLE,
    val color: ColorId = ColorId.GRIS,
    // Grants the manage_bots tool (create/list/remove local bots) for this bot's own
    // conversation only — see ManageLocalBotsTool and OrchestratorImpl.
    //
    // Also read from the key it was saved under before it was renamed, so a bot that already had
    // this power keeps it. It is written under the new name from now on.
    @JsonNames("isChiefOfStaff")
    val isChiefOfBots: Boolean = false,
)

/**
 * Bots that run entirely on this phone: a name, a custom system prompt, and their own
 * chat thread via the same on-device orchestrator the main chat screen uses — no PC
 * gateway involved. [LocalBot.id] doubles as the conversation id; [OrchestratorImpl]
 * looks it up there to swap in [LocalBot.systemPrompt] as that turn's persona.
 */
@Singleton
class LocalBotStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _bots = MutableStateFlow(load())
    val bots: StateFlow<List<LocalBot>> = _bots.asStateFlow()

    private val _chiefName = MutableStateFlow(ChiefOfBots.cleanName(prefs.getString(KEY_CHIEF_NAME, null)))

    /** What the Chief of Bots is called; it answers to this name. */
    val chiefName: StateFlow<String> = _chiefName.asStateFlow()

    /** Rename the Chief. Blank puts back the default name. */
    fun setChiefName(name: String) {
        val cleaned = ChiefOfBots.cleanName(name)
        prefs.edit().putString(KEY_CHIEF_NAME, cleaned).apply()
        _chiefName.value = cleaned
    }

    /** Create a new local bot. Returns null if [name] is blank or already taken. */
    fun add(name: String, systemPrompt: String, isChiefOfBots: Boolean = false): LocalBot? {
        val cleaned = name.trim()
        if (cleaned.isEmpty() || _bots.value.any { it.name.equals(cleaned, ignoreCase = true) }) return null
        val count = _bots.value.size
        val bot = LocalBot(
            id = "localbot_${IdGenerator.newId()}",
            name = cleaned,
            systemPrompt = systemPrompt.trim(),
            createdAt = System.currentTimeMillis(),
            // Prefer a shape/color no other bot has, so each bot looks distinct even after
            // removals (a plain round-robin on the count handed a new bot its neighbour's look).
            shape = ShapeId.entries.firstOrNull { s -> _bots.value.none { it.shape == s } }
                ?: ShapeId.entries[count % ShapeId.entries.size],
            color = BOT_COLORS.firstOrNull { c -> _bots.value.none { it.color == c } }
                ?: BOT_COLORS[count % BOT_COLORS.size],
            isChiefOfBots = isChiefOfBots,
        )
        write(_bots.value + bot)
        return bot
    }

    fun remove(id: String) {
        write(_bots.value.filterNot { it.id == id })
    }

    /**
     * Bring bots back from a backup with the ids they had, because a bot's id is also its chat's
     * conversation id and a restored chat is only reachable through the same one. [add] mints a
     * new id, which would leave the restored history attached to nothing.
     *
     * A bot whose id is already here is left alone unless [overwrite]; a bot that would take a
     * name another bot already has is skipped, since names are unique.
     */
    fun restore(backedUp: List<LocalBot>, overwrite: Boolean): ImportReport {
        var added = 0
        var replaced = 0
        var skipped = 0
        var next = _bots.value
        for (bot in backedUp) {
            val existing = next.firstOrNull { it.id == bot.id }
            when {
                existing != null && overwrite -> {
                    next = next.map { if (it.id == bot.id) bot else it }; replaced++
                }
                existing != null -> skipped++
                next.any { it.name.equals(bot.name, ignoreCase = true) } -> skipped++
                else -> {
                    next = next + bot; added++
                }
            }
        }
        if (next != _bots.value) write(next)
        return ImportReport(added, replaced, skipped)
    }

    /**
     * The custom persona for [conversationId], or null if it doesn't belong to a local bot. The
     * Chief of Bots' shared thread has one too: its on-device side answers as the Chief, by name.
     */
    fun systemPromptFor(conversationId: String): String? {
        val base = BotThreads.baseOf(conversationId)
        return if (base == ChiefOfBots.THREAD) {
            ChiefOfBots.localPersona(_chiefName.value)
        } else {
            _bots.value.firstOrNull { it.id == base }?.systemPrompt?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * True if [conversationId] is a chat with an on-device persona: one of this phone's local bots'
     * own threads, or the Chief of Bots' shared one.
     */
    fun isLocalBot(conversationId: String): Boolean {
        val base = BotThreads.baseOf(conversationId)
        return base == ChiefOfBots.THREAD || _bots.value.any { it.id == base }
    }

    /**
     * True if [conversationId] may manage bots: the Chief of Bots' thread, or the thread of a local
     * bot that was granted it.
     */
    fun isChiefOfBots(conversationId: String): Boolean {
        val base = BotThreads.baseOf(conversationId)
        return base == ChiefOfBots.THREAD || _bots.value.firstOrNull { it.id == base }?.isChiefOfBots == true
    }

    private fun load(): List<LocalBot> {
        val raw = prefs.getString(KEY_BOTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<LocalBot>>(raw) }
            .onFailure { Timber.tag("LocalBotStore").w(it, "corrupt local bot store, resetting") }
            .getOrElse { emptyList() }
    }

    private fun write(bots: List<LocalBot>) {
        prefs.edit().putString(KEY_BOTS, json.encodeToString(bots)).apply()
        _bots.value = bots
    }

    companion object {
        private const val PREFS = "local_bots"
        private const val KEY_BOTS = "bots"
        private const val KEY_CHIEF_NAME = "chief_name"

        // ENCRE is near-black and vanishes against the dark theme's background.
        private val BOT_COLORS = ColorId.entries.filter { it != ColorId.ENCRE }
    }
}
