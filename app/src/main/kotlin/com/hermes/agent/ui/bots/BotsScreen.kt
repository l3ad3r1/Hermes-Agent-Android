package com.hermes.agent.ui.bots

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.hermes.agent.data.local.BotThreads
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.local.LocalBot
import com.hermes.agent.data.local.LocalBotStore
import com.hermes.agent.data.llm.LocalLlmManager
import com.hermes.agent.data.remote.BotProfileStore
import com.hermes.agent.data.remote.ChiefOfBots
import com.hermes.agent.data.remote.GatewayApiClient
import com.hermes.agent.data.remote.GatewayEvent
import com.hermes.agent.data.remote.RedditDraft
import com.hermes.agent.data.remote.RemoteJob
import com.hermes.agent.data.remote.RemoteProfile
import com.hermes.agent.data.voice.VoiceInputEvent
import com.hermes.agent.data.voice.VoiceInputManager
import com.hermes.agent.data.voice.VoiceOutputEvent
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.data.repository.ConversationRepositoryImpl
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.ui.bloub.BloubBot
import com.hermes.agent.ui.bloub.ColorId
import com.hermes.agent.ui.bloub.ShapeId
import com.hermes.agent.ui.bloub.StateId
import com.hermes.agent.ui.chat.components.ChatInputBar
import com.hermes.agent.ui.components.RemoteJobCard
import com.hermes.agent.ui.components.SlimTopBar
import com.hermes.agent.util.IdGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Named
import java.util.Calendar

/**
 * Namespaces a desktop bot's local copy of its thread, so a PC profile called "Poet" can never
 * collide with a local bot's own conversation.
 */
private const val DESKTOP_BOT_CONVERSATION_PREFIX = "desktopbot_"

/** What a new thread is called until its first message names it. */
private const val NEW_THREAD_TITLE = "New chat"

/**
 * Pairs a stored thread back into the turns this screen shows.
 *
 * Room keeps one row per message; a turn is a user message and the reply that follows it.
 * Tool traffic is skipped — the user asked the bot something, not its tools.
 */
private fun List<Message>.toTurns(): List<BotTurn> {
    val turns = mutableListOf<BotTurn>()
    forEach { message ->
        when (message.role) {
            MessageRole.USER -> turns += BotTurn(message.content, attachment = message.attachmentUri != null)
            MessageRole.ASSISTANT -> {
                val open = turns.lastOrNull()?.takeIf { it.reply.isEmpty() }
                if (open == null) turns += BotTurn("", message.content)
                else turns[turns.lastIndex] = open.copy(reply = message.content)
            }
            else -> Unit
        }
    }
    return turns
}

/** One of a bot's chat threads, for the history list. */
data class BotThread(val id: String, val title: String, val updatedAt: Long, val current: Boolean)

/** One exchange with a bot: what was sent, and what it answered (filled in as it streams). */
data class BotTurn(
    val sent: String,
    val reply: String = "",
    val failed: Boolean = false,
    /** The question carried an image or document. */
    val attachment: Boolean = false,
)

/** Names tried on a gateway that cannot list its bots. */
private val COMMON_PROFILE_NAMES = listOf("redditbot", "research", "coder", "writer", "assistant")

/** Where a bot runs. */
enum class BotKind {
    /** An on-device persona, answering from this phone's own model. */
    LOCAL,

    /** A profile on the PC gateway. */
    DESKTOP,

    /** The Chief of Bots: the PC's default agent and the phone's own model, in one thread. */
    HYBRID,
}

/** One chip in the bot list. */
data class BotChip(val id: String, val label: String, val kind: BotKind, val shape: ShapeId, val color: ColorId) {
    val isLocal: Boolean get() = kind == BotKind.LOCAL
    val isHybrid: Boolean get() = kind == BotKind.HYBRID

    /** Whether it can take an attachment and be given a reasoning effort: it has a phone side. */
    val hasPhone: Boolean get() = kind != BotKind.DESKTOP
}

/** One bot's roundup for the Dashboard tab: what it's doing, done, or about to do. */
data class DashboardEntry(val label: String, val kind: BotKind, val status: String, val detail: String? = null)

/** The kind of a PC profile: the gateway's `default` agent is the Chief of Bots. */
private fun kindOfProfile(profile: String) = if (profile == ChiefOfBots.PROFILE) BotKind.HYBRID else BotKind.DESKTOP

/** The message that is sent to the PC failed to start, so the Chief's phone side takes it. */
private class PcUnreachable(message: String?) : Exception(message)

/**
 * A stable shape/color for a desktop bot, which — unlike a [LocalBot] — has nowhere of its
 * own to persist one. Derived from the profile name so it stays the same across restarts
 * without needing new storage.
 */
private fun visualFor(name: String): Pair<ShapeId, ColorId> {
    val shapes = ShapeId.entries
    val colors = ColorId.entries
    val h = kotlin.math.abs(name.hashCode())
    return shapes[h % shapes.size] to colors[(h / shapes.size) % colors.size]
}

data class BotsUiState(
    val bots: List<BotChip> = listOf(
        visualFor(BotProfileStore.DEFAULT).let { (shape, color) ->
            BotChip(ChiefOfBots.PROFILE, ChiefOfBots.DEFAULT_NAME, kind = BotKind.HYBRID, shape = shape, color = color)
        },
    ),
    val selected: String = BotProfileStore.DEFAULT,
    val jobs: List<RemoteJob> = emptyList(),
    val loading: Boolean = false,
    val busyJobId: String? = null,
    val sending: Boolean = false,
    /** Turns read back from the bot's stored thread. */
    val stored: List<BotTurn> = emptyList(),
    /**
     * The turn being sent right now. It has to live beside [stored] rather than in it: the
     * reply is only written down once the turn ends, so a stored snapshot taken mid-turn
     * would otherwise blank the reply the user is watching arrive.
     */
    val pending: BotTurn? = null,
    val error: String? = null,
    val configured: Boolean = true,
    // Reddit drafts are PC-wide state, not tied to the selected bot chip.
    val redditDrafts: List<RedditDraft> = emptyList(),
    val busyRedditCode: String? = null,
    val redditMessage: String? = null,
    val dashboard: List<DashboardEntry> = emptyList(),
    val dashboardLoading: Boolean = false,
    /**
     * Whether each bot can answer right now, by bot id. A bot is absent until the first check
     * has finished, which is different from being offline.
     */
    val online: Map<String, Boolean> = emptyMap(),
    /** Text for the composer, from dictation; the composer resets to it whenever it changes. */
    val inputPrefill: String = "",
    /** The microphone is open. */
    val isListening: Boolean = false,
    /** The hands-free listen/answer-aloud loop is running. */
    val voiceChatActive: Boolean = false,
    /** The app-wide reasoning effort, as the main chat's composer shows it. */
    val reasoningEffort: String = "medium",
    /** What the Chief of Bots is called; it answers to this name. */
    val chiefName: String = ChiefOfBots.DEFAULT_NAME,
    /** Whether the Chief's PC side answered its last check; null until the first has finished. */
    val chiefPcOnline: Boolean? = null,
    /** Which thread each bot is showing, where it is not its first. */
    val threadIds: Map<String, String> = emptyMap(),
    /** The selected bot's threads, newest first. */
    val threads: List<BotThread> = emptyList(),
) {
    val selectedLabel: String get() = bots.firstOrNull { it.id == selected }?.label ?: selected
    val selectedIsLocal: Boolean get() = bots.firstOrNull { it.id == selected }?.isLocal == true

    /** What the chat shows: the stored thread, with the in-flight turn laid over its tail. */
    val turns: List<BotTurn> get() = when {
        pending == null -> stored
        // The question has already been stored; only its reply is still on its way.
        stored.lastOrNull()?.sent == pending.sent -> stored.dropLast(1) + pending
        else -> stored + pending
    }
}

/**
 * The Bots hub: chat with and manage every bot this phone knows about — bots that run on the
 * PC gateway (their scheduled jobs, a thread with their agent) and bots that run entirely
 * on-device (a named persona chatting through the local orchestrator). A Dashboard tab rolls
 * all of them up into one status view.
 */
@HiltViewModel
class BotsViewModel @Inject constructor(
    private val gateway: GatewayApiClient,
    private val profileStore: BotProfileStore,
    private val localBotStore: LocalBotStore,
    // "local"-qualified ChatRepository (see LlmModule.provideLocalOnlyChatRepository) and the
    // concrete ConversationRepositoryImpl, not the DI-switched ChatRepository/
    // ConversationRepository default bindings: local bots must run on this phone's own model
    // even when "Use remote gateway" is on, since that setting rebinds the default bindings to
    // their PC-gateway implementations app-wide (see LlmModule, AgentsModule).
    @Named("local") private val chatRepository: ChatRepository,
    private val conversationRepository: ConversationRepositoryImpl,
    private val settingsRepository: SettingsRepository,
    private val toolConfirmationService: com.hermes.agent.domain.tool.ToolConfirmationService,
    private val localLlmManager: LocalLlmManager,
    private val voiceInputManager: VoiceInputManager,
    private val voiceOutputManager: VoiceOutputManager,
) : ViewModel() {

    // A local bot's chat can request a confirmation-gated tool (the Chief of Bots'
    // manage_bots); only ChatScreen used to show that dialog, so here it timed out as "declined".
    val pendingToolConfirmation = toolConfirmationService.pendingRequest

    fun submitToolConfirmation(requestId: String, approved: Boolean) {
        toolConfirmationService.submitConfirmation(requestId, approved)
    }

    private val _state = MutableStateFlow(
        BotsUiState(
            bots = chipsFor(profileStore.profiles.value, localBotStore.bots.value, localBotStore.chiefName.value),
            chiefName = localBotStore.chiefName.value,
        ),
    )
    val state: StateFlow<BotsUiState> = _state.asStateFlow()

    // Declared before `init`: its coroutines run on Main.immediate, so they start (and read this)
    // during construction, and a property declared below would still be null.
    /** Each bot's open thread where it is not its first; mirrored into the state for the screen. */
    private val threadIds = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            combine(profileStore.profiles, localBotStore.bots, localBotStore.chiefName) { profiles, locals, chiefName ->
                chipsFor(profiles, locals, chiefName) to chiefName
            }.collect { (chips, chiefName) -> _state.update { it.copy(bots = chips, chiefName = chiefName) } }
        }
        // Show the thread that is stored rather than only what this ViewModel has sent since
        // it was created: `turns` was in-memory alone, which left the chat empty after
        // switching bots or leaving the screen even though the messages were still there.
        viewModelScope.launch {
            state.map { conversationIdFor(it.selected) }.distinctUntilChanged().collectLatest { conversationId ->
                conversationRepository.observeMessages(conversationId).collect { messages ->
                    val stored = messages.toTurns()
                    _state.update { s ->
                        // Let the in-flight turn go only once the stored thread has caught up
                        // with it, reply included. Gating on `sending` instead let an emission
                        // queued mid-turn land late and blank a reply that had already arrived.
                        val settled = s.pending != null &&
                            stored.lastOrNull()?.sent == s.pending.sent &&
                            stored.last().reply.isNotEmpty()
                        s.copy(stored = stored, pending = if (settled) null else s.pending)
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(
                state.map { it.selected to it.threadIds }.distinctUntilChanged(),
                conversationRepository.observeConversations(),
            ) { (selected, _), conversations -> selected to conversations }
                .collect { (selected, conversations) ->
                    val base = baseConversationId(selected)
                    val current = conversationIdFor(selected)
                    val threads = conversations
                        .filter { BotThreads.belongsTo(it.id, base) }
                        .sortedByDescending { it.updatedAt }
                        .map { BotThread(it.id, it.title, it.updatedAt, current = it.id == current) }
                    _state.update { it.copy(threads = threads) }
                }
        }
        viewModelScope.launch {
            settingsRepository.observe()
                .map { it.reasoningEffort }
                .distinctUntilChanged()
                .collect { effort -> _state.update { it.copy(reasoningEffort = effort) } }
        }
        // Follow the settings flow rather than one snapshot: current() can hand back the
        // defaults before the store has loaded, which showed "not set up" on a cold start
        // even though the gateway was configured.
        viewModelScope.launch {
            settingsRepository.observe()
                .map { it.remoteGatewayUrl.isNotBlank() && it.remoteGatewayApiKey.isNotBlank() }
                .distinctUntilChanged()
                .collect { configured ->
                    _state.update { it.copy(configured = configured) }
                    // The screen's first dashboard refresh can run before this emits (configured
                    // still defaults to true), so redo it once the real value is known.
                    refreshDashboard()
                    if (configured) {
                        if (!_state.value.selectedIsLocal) refresh()
                        refreshRedditDrafts()
                    }
                }
        }
        // A desktop the Chief has not looked over yet: ask whether to add its bots.
        viewModelScope.launch {
            settingsRepository.observe()
                .map { if (it.remoteGatewayApiKey.isBlank()) "" else it.remoteGatewayUrl.trim().trimEnd('/') }
                .distinctUntilChanged()
                .collect { url ->
                    if (url.isNotBlank() && url != profileStore.offeredGateway) offerDesktopBotsOnConnect(url)
                }
        }
    }

    fun select(id: String) {
        if (id == _state.value.selected) return
        // Voice belongs to the bot it was started on; carrying it across would send the next
        // thing said to a different one.
        stopVoiceChat()
        // Each bot keeps its own thread; showing another bot's replies under this one would lie.
        _state.update { it.copy(selected = id, jobs = emptyList(), stored = emptyList(), pending = null, error = null) }
        if (!isLocal(id)) refresh()
    }

    /** Start a fresh thread with the selected bot, as "new chat" does in the main chat. */
    fun newThread() {
        val s = _state.value
        // An empty thread is already a new chat; a reply in flight belongs to the one it was sent in.
        if (s.sending || s.stored.isEmpty()) return
        stopVoiceChat()
        val id = BotThreads.newId(baseConversationId(s.selected))
        threadIds[s.selected] = id
        _state.update { it.copy(threadIds = threadIds.toMap(), stored = emptyList(), pending = null, error = null) }
        viewModelScope.launch { runCatching { conversationRepository.ensureConversation(id, NEW_THREAD_TITLE) } }
    }

    /** Open one of the selected bot's earlier threads. */
    fun openThread(id: String) {
        val s = _state.value
        if (s.sending || id == conversationIdFor(s.selected) || !BotThreads.belongsTo(id, baseConversationId(s.selected))) return
        stopVoiceChat()
        if (id == baseConversationId(s.selected)) threadIds.remove(s.selected) else threadIds[s.selected] = id
        _state.update { it.copy(threadIds = threadIds.toMap(), stored = emptyList(), pending = null, error = null) }
    }

    /**
     * Delete one of the selected bot's threads from this phone (K35). Deleting the open one moves
     * to the newest thread left, or to a fresh one: a PC bot's session is keyed on the thread id,
     * so reusing the deleted id would bring its history back from the PC.
     */
    fun deleteThread(id: String) {
        val s = _state.value
        val base = baseConversationId(s.selected)
        if (s.sending || !BotThreads.belongsTo(id, base)) return
        val fresh = if (id == conversationIdFor(s.selected)) {
            stopVoiceChat()
            val next = s.threads.firstOrNull { it.id != id }?.id
            val moveTo = next ?: BotThreads.newId(base)
            if (moveTo == base) threadIds.remove(s.selected) else threadIds[s.selected] = moveTo
            _state.update { it.copy(threadIds = threadIds.toMap(), stored = emptyList(), pending = null, error = null) }
            moveTo.takeIf { next == null }
        } else {
            null
        }
        viewModelScope.launch {
            runCatching {
                conversationRepository.deleteConversation(id)
                fresh?.let { conversationRepository.ensureConversation(it, NEW_THREAD_TITLE) }
            }.onFailure { e -> _state.update { it.copy(error = "Couldn't delete that chat: ${e.message}") } }
        }
    }

    fun addDesktopBot(name: String) {
        if (!profileStore.add(name)) {
            _state.update { it.copy(error = "'$name' is not a usable bot name.") }
            return
        }
        // Land on the bot just added, as addLocalBot does, and load its jobs.
        _state.update { it.copy(error = null, selected = name.trim(), jobs = emptyList(), stored = emptyList(), pending = null) }
        refresh()
    }

    /** Create a local bot: a name, a custom persona, and its own on-device chat thread. */
    fun addLocalBot(name: String, systemPrompt: String, isChiefOfBots: Boolean = false) {
        val bot = localBotStore.add(name, systemPrompt, isChiefOfBots)
        if (bot == null) {
            _state.update { it.copy(error = "'$name' is not a usable bot name.") }
            return
        }
        _state.update { it.copy(error = null, selected = bot.id, jobs = emptyList(), stored = emptyList(), pending = null) }
    }

    /** Give the Chief of Bots a name. It is written into both of its prompts, so it answers to it. */
    fun renameChief(name: String) = localBotStore.setChiefName(name)

    fun removeBot(id: String) {
        if (isLocal(id)) localBotStore.remove(id) else profileStore.remove(id)
        if (threadIds.remove(id) != null) _state.update { it.copy(threadIds = threadIds.toMap()) }
        _state.update { it.copy(selected = if (it.selected == id) BotProfileStore.DEFAULT else it.selected) }
        if (!_state.value.selectedIsLocal) refresh()
    }

    fun refresh() = viewModelScope.launch {
        if (_state.value.selectedIsLocal) {
            _state.update { it.copy(jobs = emptyList(), loading = false) }
            return@launch
        }
        if (!_state.value.configured) {
            _state.update { it.copy(loading = false, jobs = emptyList()) }
            return@launch
        }
        _state.update { it.copy(loading = true, error = null) }
        val profile = _state.value.selected
        runCatching { gateway.listJobs(profile.takeIf { it != BotProfileStore.DEFAULT }) }
            .onSuccess { jobs -> _state.update { it.copy(jobs = jobs, loading = false) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Could not reach the PC gateway.") } }
    }

    fun refreshRedditDrafts() = viewModelScope.launch {
        if (!_state.value.configured) return@launch
        runCatching { gateway.listRedditDrafts() }
            .onSuccess { drafts -> _state.update { it.copy(redditDrafts = drafts) } }
            .onFailure { e -> _state.update { it.copy(redditMessage = e.message ?: "Could not load Reddit drafts.") } }
    }

    /** Post a draft (or its critic rewrite) in the PC's logged-in Chrome window. No LLM in between. */
    fun approveRedditDraft(draft: RedditDraft, useRewrite: Boolean) = viewModelScope.launch {
        _state.update { it.copy(busyRedditCode = draft.code, redditMessage = null) }
        runCatching { gateway.approveRedditDraft(draft.code, useRewrite) }
            .onSuccess { result ->
                _state.update { s ->
                    s.copy(busyRedditCode = null, redditMessage = result, redditDrafts = s.redditDrafts.filterNot { it.code == draft.code })
                }
            }
            .onFailure { e -> _state.update { it.copy(busyRedditCode = null, redditMessage = "Post failed: ${e.message}") } }
    }

    fun skipRedditDraft(draft: RedditDraft) = viewModelScope.launch {
        _state.update { it.copy(busyRedditCode = draft.code, redditMessage = null) }
        runCatching { gateway.skipRedditDraft(draft.code) }
            .onSuccess { result ->
                _state.update { s ->
                    s.copy(busyRedditCode = null, redditMessage = result, redditDrafts = s.redditDrafts.filterNot { it.code == draft.code })
                }
            }
            .onFailure { e -> _state.update { it.copy(busyRedditCode = null, redditMessage = "Skip failed: ${e.message}") } }
    }

    /** Apply pause, resume or run to one bot's PC job, then show its new state. */
    fun act(job: RemoteJob, action: String) = viewModelScope.launch {
        _state.update { it.copy(busyJobId = job.id, error = null) }
        val profile = _state.value.selected.takeIf { it != BotProfileStore.DEFAULT }
        runCatching { gateway.jobAction(job.id, action, profile) }
            .onSuccess { updated ->
                _state.update { s ->
                    s.copy(busyJobId = null, jobs = s.jobs.map { if (it.id == updated.id) updated else it })
                }
            }
            .onFailure { e -> _state.update { it.copy(busyJobId = null, error = "$action failed: ${e.message}") } }
    }

    private var sendJob: Job? = null

    /** The PC run being waited on, so Stop can end it there and not just stop listening. */
    private var activeRun: Pair<String, String?>? = null

    /**
     * Send a message to the selected bot — its PC agent, or its on-device persona. Only a local
     * bot can be sent an attachment; a PC run takes text alone.
     */
    fun send(text: String, attachmentUri: String? = null, attachmentMimeType: String? = null) {
        val message = text.trim()
        if ((message.isEmpty() && attachmentUri.isNullOrBlank()) || _state.value.sending) return
        val selected = _state.value.selected
        val thread = conversationIdFor(selected)
        if (_state.value.stored.isEmpty() && BotThreads.isLater(thread)) {
            val title = message.ifBlank { "Attachment" }.replace(Regex("\\s+"), " ").take(40)
            viewModelScope.launch { runCatching { conversationRepository.renameConversation(thread, title) } }
        }
        _state.update {
            it.copy(
                sending = true,
                error = null,
                inputPrefill = "",
                pending = BotTurn(message, attachment = !attachmentUri.isNullOrBlank()),
            )
        }

        sendJob = viewModelScope.launch {
            // Null when the turn failed, so the hands-free loop knows there is nothing to read out.
            var reply: String? = null
            // Nothing a bot's run throws may escape this coroutine: it runs on viewModelScope, so an
            // uncaught network error here takes the whole app down.
            try {
                reply = when {
                    isLocal(selected) -> sendLocalBot(selected, message, attachmentUri, attachmentMimeType)
                    isHybrid(selected) -> sendChief(message, attachmentUri, attachmentMimeType)
                    else -> sendRemote(selected, message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.failPending(e.message).copy(sending = false) }
            }
            if (_state.value.voiceChatActive) {
                if (reply.isNullOrBlank()) listenForNextTurn() else speakThenListen(reply)
            }
        }
    }

    /**
     * Stop the reply in progress. A PC run keeps going on the PC unless it is stopped there too.
     */
    fun cancel() {
        sendJob?.cancel()
        sendJob = null
        activeRun?.let { (runId, profile) ->
            activeRun = null
            viewModelScope.launch { runCatching { gateway.stopRun(runId, profile) } }
        }
        _state.update { it.copy(sending = false, pending = null) }
        // Hands-free: take the turn back rather than leave the loop waiting on a reply that will
        // not come.
        if (_state.value.voiceChatActive) listenForNextTurn()
    }

    fun setReasoningEffort(effort: String) {
        viewModelScope.launch { settingsRepository.setReasoningEffort(effort) }
    }

    /** The reply text, or null when the turn failed. */
    private suspend fun sendLocalBot(
        botId: String,
        message: String,
        attachmentUri: String?,
        attachmentMimeType: String?,
    ): String? {
        val bot = localBotStore.bots.value.firstOrNull { it.id == botId }
        if (bot == null) {
            _state.update { it.failPending("This local bot no longer exists.").copy(sending = false) }
            return null
        }
        return sendOnPhone(conversationIdFor(bot.id), bot.name, message, attachmentUri, attachmentMimeType)
    }

    /**
     * The Chief of Bots: one thread, two brains. [ChiefOfBots.route] picks who answers. If the PC
     * turns out to be unreachable the phone takes the message, so the Chief always answers.
     */
    private suspend fun sendChief(message: String, attachmentUri: String?, attachmentMimeType: String?): String? {
        val s = _state.value
        // A yes or no to "want me to add the PC's bots?", which is the app's to act on, not a model's.
        if (attachmentUri.isNullOrBlank()) answerOffer(message)?.let { return it }
        val phoneBots = localBotStore.bots.value.map { it.name }
        val route = ChiefOfBots.route(
            message,
            hasAttachment = !attachmentUri.isNullOrBlank(),
            // Not yet checked counts as reachable: try the PC, and fall back if it is not.
            pcOnline = s.configured && s.chiefPcOnline != false,
            phoneBots = phoneBots,
        )
        // Only asking to see the bots: the app holds the lists, so it answers, with no model to get it wrong.
        if (route == ChiefOfBots.Route.PHONE && attachmentUri.isNullOrBlank() && ChiefOfBots.isBotListRequest(message)) {
            return answerBotList(message)
        }
        if (route == ChiefOfBots.Route.PHONE && attachmentUri.isNullOrBlank() && ChiefOfBots.isDiscoverRequest(message)) {
            val offer = desktopBotOffer()
            if (offer.names.isNotEmpty()) profileStore.openOffer = offer.names
            return finishLocalTurn(message, offer.text)
        }
        // A clear "create a bot named X" / "remove the bot named X": the app does it itself.
        if (route == ChiefOfBots.Route.PHONE && attachmentUri.isNullOrBlank()) {
            ChiefOfBots.parseBotCommand(message, phoneBots)?.let { return runBotCommand(message, it) }
        }
        if (route == ChiefOfBots.Route.PC) {
            try {
                return sendRemote(ChiefOfBots.PROFILE, message)
            } catch (e: PcUnreachable) {
                // Remember it, so the next message goes straight to the phone, and say why this
                // one did.
                _state.update {
                    it.copy(
                        chiefPcOnline = false,
                        error = "The PC could not be reached, so ${it.chiefName} answered on the phone.",
                    )
                }
            }
        }
        return sendOnPhone(conversationIdFor(ChiefOfBots.PROFILE), _state.value.chiefName, message, attachmentUri, attachmentMimeType)
    }

    /** What the PC has that this phone does not, and how the Chief puts it. */
    private class DesktopOffer(val names: List<String>, val text: String, val reachable: Boolean = true)

    /**
     * Looks over the connected PC for bots not yet in the tabs: the gateway's own list when it has
     * one, otherwise (an older gateway) a probe of a few common names.
     */
    private suspend fun desktopBotOffer(): DesktopOffer {
        val known = profileStore.profiles.value.toSet()
        val listed = try {
            gateway.listProfiles()
        } catch (e: Exception) {
            return DesktopOffer(emptyList(), "I couldn't reach the PC to look: ${e.message ?: "no answer"}.", reachable = false)
        }
        val profiles = listed ?: COMMON_PROFILE_NAMES
            .filter { runCatching { gateway.profileExists(it) }.getOrDefault(false) }
            .map { RemoteProfile(it) }
        val fresh = profiles.filter {
            it.name != BotProfileStore.DEFAULT && BotProfileStore.VALID.matches(it.name) && it.name !in known
        }
        if (fresh.isEmpty()) {
            return DesktopOffer(
                emptyList(),
                "I looked on the PC and every bot it has is already in your tabs." +
                    if (listed == null) " (This gateway can't list its bots, so I only tried common names.)" else "",
            )
        }
        val one = fresh.size == 1
        val lines = fresh.joinToString("\n") { p ->
            "\u2022 ${p.name}" + p.description.takeIf { it.isNotBlank() }?.let { " \u2014 $it" }.orEmpty()
        }
        return DesktopOffer(
            fresh.map { it.name },
            "This PC has ${if (one) "a bot" else "${fresh.size} bots"} that ${if (one) "isn't" else "aren't"} in your tabs yet:\n$lines\n\n" +
                "Want me to add ${if (one) "it" else "them"}? Say yes, no, or name the ones you want.",
        )
    }

    /** On connecting to a desktop not seen before: post the offer in the Chief's thread, unasked. */
    private suspend fun offerDesktopBotsOnConnect(url: String) {
        val offer = runCatching { desktopBotOffer() }.getOrNull() ?: return
        // Could not reach it: leave it unmarked, so the next launch tries again.
        if (!offer.reachable) return
        profileStore.offeredGateway = url
        if (offer.names.isEmpty()) return
        profileStore.openOffer = offer.names
        val chiefName = _state.value.chiefName
        val thread = conversationIdFor(ChiefOfBots.PROFILE)
        runCatching {
            conversationRepository.ensureConversation(thread, chiefName)
            conversationRepository.addMessage(thread, botMessage(thread, MessageRole.ASSISTANT, offer.text))
        }
    }

    /** Acts on a reply to an open offer, or returns null when the message is about something else. */
    private suspend fun answerOffer(message: String): String? {
        val offered = profileStore.openOffer.takeIf { it.isNotEmpty() } ?: return null
        val reply = ChiefOfBots.parseOfferReply(message, offered) ?: return null
        val chosen = when (reply) {
            ChiefOfBots.OfferReply.All -> offered
            ChiefOfBots.OfferReply.None -> emptyList()
            is ChiefOfBots.OfferReply.Some -> reply.names
        }
        val text = if (chosen.isEmpty()) {
            profileStore.openOffer = emptyList()
            "Okay, I'll leave them out. Ask me to \"find the bots on my PC\" whenever you want them."
        } else {
            val added = chosen.filter { profileStore.add(it) }
            val rest = if (reply is ChiefOfBots.OfferReply.Some) offered - chosen.toSet() else emptyList()
            profileStore.openOffer = rest
            buildString {
                append(
                    if (added.isEmpty()) "They were already added."
                    else "Added ${added.joinToString(", ")}. ${if (added.size == 1) "It's" else "They're"} in your bot tabs.",
                )
                if (rest.isNotEmpty()) append(" Still on offer: ${rest.joinToString(", ")}. Say yes to add ${if (rest.size == 1) "it" else "them"} too.")
            }
        }
        return finishLocalTurn(message, text)
    }

    /** Which bots exist, read from the app's own lists and stored like any other turn. */
    private suspend fun answerBotList(message: String): String {
        val s = _state.value
        val onPhone = s.bots.filter { it.isLocal }.map { it.label }
        val onPc = s.bots.filter { !it.isLocal }.map { if (it.isHybrid) "${it.label} (me)" else it.label }
        val reply = "On this phone: ${onPhone.joinToString(", ").ifEmpty { "none yet" }}.\n" +
            "On the PC (added here): ${onPc.joinToString(", ").ifEmpty { "none" }}."
        return finishLocalTurn(message, reply)
    }

    /**
     * Creates or removes a phone bot on the user's word, without asking a model to.
     *
     * It goes through the same confirmation the `manage_bots` tool does — auto-approved when the
     * user has switched phone actions on, otherwise the Allow/Deny dialog — so doing it directly
     * takes no permission the tool call would not have needed.
     */
    private suspend fun runBotCommand(message: String, command: ChiefOfBots.BotCommand): String {
        val call = ToolCall(
            id = IdGenerator.newId(),
            name = "manage_bots",
            arguments = mapOf(
                "action" to JsonPrimitive(if (command is ChiefOfBots.BotCommand.Create) "create" else "remove"),
                "name" to JsonPrimitive(
                    when (command) {
                        is ChiefOfBots.BotCommand.Create -> command.name
                        is ChiefOfBots.BotCommand.Remove -> command.name
                    },
                ),
            ),
        )
        val reply = if (!toolConfirmationService.awaitConfirmation(call)) {
            "Okay, I left it alone."
        } else {
            when (command) {
                is ChiefOfBots.BotCommand.Create -> {
                    val persona = command.purpose
                        ?.let { "You are ${command.name}. Your job: $it." }
                        ?: "You are ${command.name}, a focused assistant."
                    val bot = localBotStore.add(command.name, persona)
                    if (bot == null) {
                        "I couldn't create \u201c${command.name}\u201d: that name is already taken."
                    } else {
                        "Created \u201c${bot.name}\u201d. It's in your bot tabs."
                    }
                }
                is ChiefOfBots.BotCommand.Remove -> {
                    val bot = localBotStore.bots.value.firstOrNull { it.name.equals(command.name, ignoreCase = true) }
                    if (bot == null) {
                        "There's no bot named \u201c${command.name}\u201d on this phone."
                    } else {
                        localBotStore.remove(bot.id)
                        "Removed \u201c${bot.name}\u201d."
                    }
                }
            }
        }
        return finishLocalTurn(message, reply)
    }

    /** Stores a turn the app answered itself, exactly as if the model had, and shows it. */
    private suspend fun finishLocalTurn(message: String, reply: String): String {
        val chiefName = _state.value.chiefName
        val thread = conversationIdFor(ChiefOfBots.PROFILE)
        runCatching {
            conversationRepository.ensureConversation(thread, chiefName)
            conversationRepository.addMessage(thread, botMessage(thread, MessageRole.USER, message))
            conversationRepository.addMessage(thread, botMessage(thread, MessageRole.ASSISTANT, reply))
        }
        _state.update { it.copy(pending = it.pending?.copy(reply = reply), sending = false) }
        return reply
    }

    /** Runs [message] through the on-device orchestrator in [conversationId]; the reply text, or null on failure. */
    private suspend fun sendOnPhone(
        conversationId: String,
        name: String,
        message: String,
        attachmentUri: String?,
        attachmentMimeType: String?,
    ): String? {
        runCatching { conversationRepository.ensureConversation(conversationId, name) }
        val reply = StringBuilder()
        var failed = false
        try {
            chatRepository.sendMessageOrchestrated(
                conversationId = conversationId,
                content = message,
                origin = ExecutionOrigin.INTERACTIVE,
                attachmentUri = attachmentUri,
                attachmentMimeType = attachmentMimeType,
            ).collect { event ->
                when (event) {
                    is OrchestratorEvent.ReplyToken -> reply.append(event.text)
                    is OrchestratorEvent.ReplyComplete -> if (reply.isEmpty()) reply.append(event.finalText)
                    is OrchestratorEvent.Failed -> {
                        failed = true
                        _state.update { s -> s.failPending(event.message) }
                        return@collect
                    }
                    else -> Unit
                }
                val sofar = reply.toString()
                _state.update { s -> s.copy(pending = s.pending?.copy(reply = sofar)) }
            }
        } catch (e: CancellationException) {
            // Stopping the reply cancels this; treating that as a failure would put "was
            // cancelled" into a turn Stop has just cleared.
            throw e
        } catch (e: Exception) {
            failed = true
            _state.update { s -> s.failPending(e.message) }
        }
        _state.update { it.copy(sending = false) }
        return reply.toString().trim().takeIf { !failed && it.isNotEmpty() }
    }

    /**
     * Where a bot's thread is stored on this phone.
     *
     * A local bot's conversation is its own id. A desktop bot's thread really lives on the PC,
     * but the phone keeps its own copy under a prefixed id so the chat survives a tab switch,
     * a restart, and the gateway being unreachable.
     */
    private fun baseConversationId(botId: String) = when {
        isLocal(botId) -> botId
        // Both of the Chief's brains write to the one thread.
        isHybrid(botId) -> ChiefOfBots.THREAD
        else -> "$DESKTOP_BOT_CONVERSATION_PREFIX$botId"
    }

    /** The thread of [botId] that is on screen: its first, until a new one is started or opened. */
    private fun conversationIdFor(botId: String) = threadIds[botId] ?: baseConversationId(botId)

    private fun botMessage(conversationId: String, role: MessageRole, content: String) = Message(
        id = IdGenerator.newId(),
        conversationId = conversationId,
        role = role,
        content = content,
        timestamp = System.currentTimeMillis(),
        tokens = (content.length / 4).coerceAtLeast(1),
        isOnDevice = false,
    )

    /** The reply text, or null when the turn failed. */
    private suspend fun sendRemote(profileId: String, message: String): String? {
        val conversationId = conversationIdFor(profileId)
        val profile = profileId.takeIf { it != BotProfileStore.DEFAULT }
        // A stable per-bot session id, like the conversation id RemoteOrchestrator passes for
        // the main chat — without it every send started a fresh, contextless run (the gateway
        // never remembered the previous turn even though the UI showed a running thread).
        val sessionId = if (BotThreads.isLater(conversationId)) {
            "phone-bot-$profileId-${conversationId.substringAfter(BotThreads.SEPARATOR)}"
        } else {
            "phone-bot-$profileId"
        }
        // The Chief of Bots' charter, with its name, rides along with every run to it, so it acts
        // as one from this app without anything on the PC being edited. Any other bot is left as
        // it is.
        val instructions = ChiefOfBots.charter(_state.value.chiefName).takeIf { profileId == ChiefOfBots.PROFILE }
        val runId = runCatching {
            gateway.startRun(message, sessionId = sessionId, profile = profile, instructions = instructions)
        }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                // The Chief has a phone side to fall back on; any other bot has nothing else.
                if (profileId == ChiefOfBots.PROFILE) throw PcUnreachable(e.message)
                _state.update { s -> s.failPending(e.message).copy(sending = false) }
                return null
            }
        // Stored once the run has started, not before: had it failed, the Chief's phone side would
        // answer through the orchestrator, which stores the question itself — twice otherwise.
        // Persisting is best-effort: a storage failure must not cost the user their reply.
        runCatching {
            conversationRepository.ensureConversation(conversationId, nameOf(profileId))
            conversationRepository.addMessage(conversationId, botMessage(conversationId, MessageRole.USER, message))
        }

        val reply = StringBuilder()
        var failed = false
        activeRun = runId to profile
        try {
            gateway.streamRunEvents(runId, profile).collect { event ->
                when (event) {
                    is GatewayEvent.MessageDelta -> reply.append(event.text)
                    is GatewayEvent.MessageComplete -> if (reply.isEmpty()) reply.append(event.text)
                    // Only used when nothing streamed: run.completed can carry raw tool output.
                    is GatewayEvent.RunCompleted -> if (reply.isEmpty()) reply.append(event.output)
                    is GatewayEvent.ApprovalRequested -> {
                        // The PC is waiting on a yes or no. Ignored, the run would sit there
                        // while this chat spun; it is asked for with the same dialog a local
                        // bot's tool confirmation uses, then answered on the run's own profile.
                        val approved = toolConfirmationService.awaitConfirmation(approvalCall(event))
                        gateway.submitApproval(runId, approved, event.requestId, profile)
                    }
                    is GatewayEvent.RunFailed -> {
                        failed = true
                        _state.update { s -> s.failPending(event.message) }
                        return@collect
                    }
                    else -> Unit
                }
                val sofar = reply.toString()
                _state.update { s -> s.copy(pending = s.pending?.copy(reply = sofar)) }
            }
        } finally {
            activeRun = null
        }
        // Only a real reply is stored. A failed run leaves the question on its own, which is
        // what happens to a local bot's failed turn too — a half-finished reply stored beside
        // the failure would hide the error once the stored thread caught up.
        val finalReply = reply.toString().trim().takeIf { !failed && it.isNotEmpty() }
        finalReply?.let {
            runCatching {
                conversationRepository.addMessage(
                    conversationId,
                    botMessage(conversationId, MessageRole.ASSISTANT, it),
                )
            }
        }
        _state.update { it.copy(sending = false) }
        return finalReply
    }

    // ── Voice ─────────────────────────────────────────────────────────────────────────────
    // The same cycle the main chat runs: dictation fills the composer, and hands-free voice chat
    // is listen → send → speak the answer → listen, never two of those at once — overlapping them
    // has the recogniser hear the reply coming out of the speaker and answer the bot with it.

    private var listenJob: Job? = null
    private var speakJob: Job? = null

    /** Consecutive voice-chat turns that yielded no usable transcript. */
    private var emptyVoiceTurns = 0

    /** Dictation: fills the composer, leaving you to send. */
    fun toggleVoiceInput() {
        if (_state.value.isListening) stopVoiceInput() else startVoiceInput()
    }

    fun toggleVoiceChat() {
        if (_state.value.voiceChatActive) stopVoiceChat() else startVoiceChat()
    }

    /** Ends dictation and voice chat, for when the chat leaves the screen. */
    fun stopVoice() = stopVoiceChat()

    private fun startVoiceChat() {
        if (!voiceInputManager.isAvailable()) {
            _state.update { it.copy(error = "Speech recognition not available on this device") }
            return
        }
        _state.update { it.copy(voiceChatActive = true) }
        emptyVoiceTurns = 0
        // Warm the engine now: the first speak() otherwise arrives before it is ready and is
        // dropped, so the first answer is silent and the loop never gets its Done to listen on.
        voiceOutputManager.initialize()
        startVoiceInput()
    }

    private fun stopVoiceChat() {
        _state.update { it.copy(voiceChatActive = false) }
        speakJob?.cancel()
        speakJob = null
        voiceOutputManager.stop()
        stopVoiceInput()
    }

    /**
     * Listen again for the next turn, if voice chat is still on. Gives up after
     * [MAX_EMPTY_VOICE_TURNS] turns that produced nothing: the loop restarts the recogniser the
     * instant it finishes, so one that fails immediately — no microphone permission — would
     * otherwise spin as fast as the CPU allows, forever.
     */
    private fun listenForNextTurn() {
        if (!_state.value.voiceChatActive) return
        if (emptyVoiceTurns >= MAX_EMPTY_VOICE_TURNS) {
            stopVoiceChat()
            _state.update { it.copy(error = "Voice chat stopped — I couldn't hear anything.") }
            return
        }
        startVoiceInput()
    }

    /** Read [text] aloud, then hand the turn back to the microphone — never before it is done. */
    private fun speakThenListen(text: String) {
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            if (!voiceOutputManager.isAvailable()) {
                // No engine: skip the speaking half rather than stall the loop.
                listenForNextTurn()
                return@launch
            }
            voiceOutputManager.speak(text).collect { event ->
                when (event) {
                    // Both terminal states hand the turn back — a speech failure should not
                    // silently end the conversation.
                    VoiceOutputEvent.Done -> listenForNextTurn()
                    is VoiceOutputEvent.Error -> listenForNextTurn()
                    VoiceOutputEvent.Start -> Unit
                }
            }
        }
    }

    private fun startVoiceInput() {
        if (!voiceInputManager.isAvailable()) {
            _state.update { it.copy(error = "Speech recognition not available on this device") }
            return
        }
        listenJob?.cancel()
        _state.update { it.copy(isListening = true) }
        listenJob = viewModelScope.launch {
            voiceInputManager.listen().collect { event ->
                when (event) {
                    is VoiceInputEvent.Partial -> _state.update { it.copy(inputPrefill = event.text) }
                    is VoiceInputEvent.Final -> {
                        _state.update { it.copy(isListening = false) }
                        if (_state.value.voiceChatActive) {
                            // Hands-free: send it rather than park it in the composer for a tap
                            // that will never come.
                            _state.update { it.copy(inputPrefill = "") }
                            if (event.text.isNotBlank()) {
                                emptyVoiceTurns = 0
                                send(event.text)
                            } else {
                                emptyVoiceTurns++
                                listenForNextTurn()
                            }
                        } else {
                            _state.update { it.copy(inputPrefill = event.text) }
                        }
                    }
                    is VoiceInputEvent.Error -> {
                        _state.update { it.copy(isListening = false) }
                        // Recogniser errors are routine in a hands-free loop — silence times
                        // out — so just take the turn again rather than bury the chat in them.
                        if (_state.value.voiceChatActive) {
                            emptyVoiceTurns++
                            listenForNextTurn()
                        } else {
                            _state.update { it.copy(error = event.message) }
                        }
                    }
                    VoiceInputEvent.Ready -> Unit
                }
            }
        }
    }

    private fun stopVoiceInput() {
        listenJob?.cancel()
        listenJob = null
        _state.update { it.copy(isListening = false) }
    }

    override fun onCleared() {
        super.onCleared()
        voiceOutputManager.stop()
    }

    private companion object {
        /** Turns of silence or recogniser failure before voice chat gives up. */
        const val MAX_EMPTY_VOICE_TURNS = 3
    }

    /** Roundup for the Dashboard tab: every local persona, plus every desktop profile's PC jobs. */
    private var dashboardJob: kotlinx.coroutines.Job? = null

    /**
     * Latest call wins: an earlier, slower refresh must not overwrite a newer result.
     *
     * [quiet] is for the background poll: it refreshes who is online without putting the
     * Dashboard tab into its loading state every time.
     */
    fun refreshDashboard(quiet: Boolean = false) {
        dashboardJob?.cancel()
        dashboardJob = viewModelScope.launch { loadDashboard(quiet) }
    }

    private suspend fun loadDashboard(quiet: Boolean) {
        if (!quiet) _state.update { it.copy(dashboardLoading = true) }
        val entries = mutableListOf<DashboardEntry>()
        val online = mutableMapOf<String, Boolean>()

        // A local bot always answers from this phone's own model, so it is available exactly
        // when that model is present.
        val modelReady = runCatching { localLlmManager.isModelDownloaded() }.getOrDefault(false)
        localBotStore.bots.value.forEach { bot ->
            entries += DashboardEntry(label = bot.name, kind = BotKind.LOCAL, status = "On-device persona")
            online[bot.id] = modelReady
        }

        // The Chief has both a PC side and a phone side, so it is available while either answers.
        var chiefPcOnline: Boolean? = null
        if (_state.value.configured) {
            gateway.listJobsByProfile(profileStore.profiles.value).forEach { (profile, result) ->
                val chief = profile == ChiefOfBots.PROFILE
                // The jobs request doubles as the reachability probe for that profile.
                online[profile] = result.isSuccess || (chief && modelReady)
                if (chief) chiefPcOnline = result.isSuccess
                entries += result.fold(
                    onSuccess = { jobs -> DashboardEntry(nameOf(profile), kindOfProfile(profile), statusFor(jobs), detailFor(jobs)) },
                    onFailure = { e ->
                        val status = if (chief && modelReady) "PC unreachable — answering on the phone" else "Unreachable"
                        DashboardEntry(nameOf(profile), kindOfProfile(profile), status, e.message)
                    },
                )
            }
        } else {
            profileStore.profiles.value.forEach { profile ->
                val chief = profile == ChiefOfBots.PROFILE
                online[profile] = chief && modelReady
                if (chief) chiefPcOnline = false
                val status = if (chief && modelReady) "PC gateway not set up — answering on the phone" else "PC gateway not set up"
                entries += DashboardEntry(nameOf(profile), kindOfProfile(profile), status)
            }
        }

        _state.update {
            it.copy(
                dashboard = entries,
                online = online,
                chiefPcOnline = chiefPcOnline ?: it.chiefPcOnline,
                dashboardLoading = false,
            )
        }
    }

    private fun statusFor(jobs: List<RemoteJob>): String {
        if (jobs.isEmpty()) return "No scheduled bots"
        val active = jobs.count { it.enabled }
        return "$active/${jobs.size} active"
    }

    private fun detailFor(jobs: List<RemoteJob>): String? {
        jobs.firstOrNull { !it.lastError.isNullOrBlank() }?.let { return "${it.name.ifBlank { it.id }} failed: ${it.lastError}" }
        jobs.firstOrNull { it.nextRunAt != null }?.let { return "Next: ${it.nextRunAt}" }
        jobs.firstOrNull { it.lastRunAt != null }?.let { return "Last: ${it.lastRunAt} ${it.lastStatus.orEmpty()}".trim() }
        return null
    }

    private fun isLocal(id: String) = _state.value.bots.firstOrNull { it.id == id }?.isLocal == true

    private fun isHybrid(id: String) = _state.value.bots.firstOrNull { it.id == id }?.isHybrid == true

    /** What a PC profile is called on screen: the Chief goes by the name it was given. */
    private fun nameOf(profile: String) = if (profile == ChiefOfBots.PROFILE) _state.value.chiefName else profile

    private fun chipsFor(profiles: List<String>, locals: List<LocalBot>, chiefName: String): List<BotChip> =
        profiles.map { name ->
            val (shape, color) = visualFor(name)
            BotChip(name, if (name == ChiefOfBots.PROFILE) chiefName else name, kindOfProfile(name), shape, color)
        } + locals.map { bot -> BotChip(bot.id, bot.name, BotKind.LOCAL, bot.shape, bot.color) }

    /** The PC's approval request, as the tool call the confirmation dialog knows how to show. */
    private fun approvalCall(event: GatewayEvent.ApprovalRequested): ToolCall {
        val arguments = runCatching { Json.parseToJsonElement(event.arguments) as? JsonObject }
            .getOrNull()?.toMap().orEmpty()
        return ToolCall(
            id = event.callId.ifBlank { IdGenerator.newId() },
            name = event.toolName,
            arguments = arguments,
        )
    }

    private fun BotsUiState.failPending(message: String?) =
        copy(pending = pending?.copy(reply = message ?: "The run failed.", failed = true))

}

/** Inside one bot's own tab: a regular chat thread, its PC jobs, and PC-wide approvals. */
private enum class BotDetailTab(val label: String) { CHAT("Chat"), JOBS("Scheduled Jobs"), APPROVALS("Approvals") }

/**
 * Top-level layout: Dashboard first, then one tab per bot — mirroring the desktop app's
 * bot list. Each bot's own tab nests Chat, Scheduled Jobs and Approvals; long-pressing a bot's tab
 * opens its menu (open chat, remove).
 */
@Composable
fun BotsScreen(
    onBack: () -> Unit,
    onOpenConnections: () -> Unit,
    viewModel: BotsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDashboard by rememberSaveable { mutableStateOf(true) }
    var detailTab by rememberSaveable { mutableStateOf(BotDetailTab.CHAT) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRenameChief by remember { mutableStateOf(false) }
    var noticeDismissed by rememberSaveable { mutableStateOf(false) }
    val pendingConfirmation by viewModel.pendingToolConfirmation.collectAsStateWithLifecycle()

    pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.submitToolConfirmation(pending.id, false) },
            title = { Text("Approve Action") },
            text = {
                Column {
                    Text("This bot wants to run: ${pending.call.name}", fontWeight = FontWeight.Bold)
                    Text(
                        pending.call.arguments.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.submitToolConfirmation(pending.id, true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { viewModel.submitToolConfirmation(pending.id, false) }) { Text("Deny") } },
        )
    }

    // The tabs show who is online, so keep that fresh while the screen is up. Cancelled with the
    // composition, so nothing polls once the user has left.
    LaunchedEffect(Unit) {
        viewModel.refreshDashboard()
        while (true) {
            delay(AVAILABILITY_POLL_MS)
            viewModel.refreshDashboard(quiet = true)
        }
    }
    val hourOfDay = rememberHourOfDay()

    if (showAddDialog) {
        AddBotDialog(
            onDismiss = { showAddDialog = false },
            onAddDesktop = { name -> viewModel.addDesktopBot(name); showDashboard = false },
            onAddLocal = { name, prompt, chief -> viewModel.addLocalBot(name, prompt, chief); showDashboard = false },
        )
    }

    if (showRenameChief) {
        RenameChiefDialog(
            current = state.chiefName,
            onDismiss = { showRenameChief = false },
            onSave = { name -> viewModel.renameChief(name) },
        )
    }

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Bots",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add a bot")
                    }
                    IconButton(
                        onClick = {
                            if (showDashboard) viewModel.refreshDashboard() else viewModel.refresh()
                            viewModel.refreshRedditDrafts()
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        // safeDrawing rather than the default systemBars: it folds the keyboard into the same
        // inset, so the composer rises above it. In landscape the IME is tall enough to cover
        // the composer completely, leaving no way to send. Using the one inset also avoids the
        // double-counting an extra imePadding() inside the content would cause.
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (!state.configured && !noticeDismissed) {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp).padding(end = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("The PC gateway is not set up yet.", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Add its URL and API key, then come back here. Local bots work without it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onOpenConnections) { Text("Open Connections") }
                        }
                        IconButton(
                            onClick = { noticeDismissed = true },
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Icon(Icons.Outlined.Cancel, contentDescription = "Dismiss", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            val selectedBotIndex = state.bots.indexOfFirst { it.id == state.selected }
            val selectedTabIndex = if (showDashboard) 0 else (selectedBotIndex + 1).coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                // The default indicator indexes tabPositions[selectedTabIndex] and crashes for a
                // frame when a bot is added (index one past the tabs measured so far).
                indicator = { positions ->
                    if (selectedTabIndex < positions.size) {
                        TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[selectedTabIndex]))
                    }
                },
            ) {
                Tab(
                    selected = showDashboard,
                    onClick = { showDashboard = true; viewModel.refreshDashboard() },
                    text = { Text("Dashboard") },
                )
                state.bots.forEach { bot ->
                    BotTab(
                        bot = bot,
                        selected = !showDashboard && bot.id == state.selected,
                        online = state.online[bot.id],
                        thinking = state.sending && bot.id == state.selected,
                        hourOfDay = hourOfDay,
                        onSelect = {
                            showDashboard = false
                            viewModel.select(bot.id)
                        },
                        onOpenChat = {
                            showDashboard = false
                            viewModel.select(bot.id)
                            detailTab = BotDetailTab.CHAT
                        },
                        onRemove = { viewModel.removeBot(bot.id) },
                        onRename = { showRenameChief = true },
                    )
                }
            }

            if (showDashboard) {
                DashboardTab(entries = state.dashboard, loading = state.dashboardLoading)
            } else {
                val bot = state.bots.getOrNull(selectedBotIndex)
                if (bot != null) {
                    state.error?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    TabRow(selectedTabIndex = detailTab.ordinal) {
                        Tab(
                            selected = detailTab == BotDetailTab.CHAT,
                            onClick = { detailTab = BotDetailTab.CHAT },
                            text = { Text(BotDetailTab.CHAT.label) },
                        )
                        Tab(
                            selected = detailTab == BotDetailTab.JOBS,
                            onClick = { detailTab = BotDetailTab.JOBS },
                            text = { Text(if (bot.isLocal) BotDetailTab.JOBS.label else "${BotDetailTab.JOBS.label} (${state.jobs.size})") },
                        )
                        Tab(
                            selected = detailTab == BotDetailTab.APPROVALS,
                            onClick = { detailTab = BotDetailTab.APPROVALS },
                            text = {
                                Text(
                                    if (!bot.isLocal && state.redditDrafts.isNotEmpty()) "${BotDetailTab.APPROVALS.label} (${state.redditDrafts.size})"
                                    else BotDetailTab.APPROVALS.label,
                                )
                            },
                        )
                    }

                    when (detailTab) {
                        BotDetailTab.CHAT -> ChatTab(
                            bot = bot,
                            turns = state.turns,
                            sending = state.sending,
                            isListening = state.isListening,
                            voiceChatActive = state.voiceChatActive,
                            inputPrefill = state.inputPrefill,
                            reasoningEffort = state.reasoningEffort,
                            pcOnline = state.configured && state.chiefPcOnline != false,
                            onSend = viewModel::send,
                            onCancel = viewModel::cancel,
                            onMicToggle = viewModel::toggleVoiceInput,
                            onVoiceChatToggle = viewModel::toggleVoiceChat,
                            onReasoningEffortChange = viewModel::setReasoningEffort,
                            onLeave = viewModel::stopVoice,
                            threads = state.threads,
                            onNewThread = viewModel::newThread,
                            onOpenThread = viewModel::openThread,
                            onDeleteThread = viewModel::deleteThread,
                        )
                        BotDetailTab.JOBS -> JobsTab(
                            jobs = state.jobs,
                            loading = state.loading,
                            isLocal = bot.isLocal,
                            configured = state.configured,
                            busyJobId = state.busyJobId,
                            onAction = { job, action -> viewModel.act(job, action) },
                        )
                        BotDetailTab.APPROVALS -> ApprovalsTab(
                            isLocal = bot.isLocal,
                            drafts = state.redditDrafts,
                            busyCode = state.busyRedditCode,
                            message = state.redditMessage,
                            onApprove = { draftItem, useRewrite -> viewModel.approveRedditDraft(draftItem, useRewrite) },
                            onSkip = { draftItem -> viewModel.skipRedditDraft(draftItem) },
                        )
                    }
                }
            }
        }
    }
}

private const val BOT_MANAGER_PROMPT =
    "You are this phone's bot manager: you help the user set up and organize the bots on it. " +
        "When they ask for a new bot, agree its purpose, then create it with the manage_bots tool " +
        "(action='create', a short name, and a focused system prompt). Use action='list' to review " +
        "the bots that exist and action='remove' to clean up ones they no longer want. " +
        "Only act when the user asks."

/**
 * One bot's tab: just its Bloub, with a badge — L for a bot on this phone, D for one on the PC, C
 * for the Chief of Bots, which is both — that is green while the bot is available and red while it
 * is not. Tap to open it, long-press for its menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotTab(
    bot: BotChip,
    selected: Boolean,
    online: Boolean?,
    thinking: Boolean,
    hourOfDay: Int,
    onSelect: () -> Unit,
    onOpenChat: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // A quick wink when the tab is tapped, so the Bloub acknowledges it.
    var winking by remember { mutableStateOf(false) }
    LaunchedEffect(winking) {
        if (winking) {
            delay(WINK_MS)
            winking = false
        }
    }
    val face = botFace(hourOfDay, bot.id, thinking, online).let { resting ->
        if (winking && !thinking) resting.copy(state = StateId.WINK) else resting
    }
    val kind = when (bot.kind) {
        BotKind.LOCAL -> "on-device"
        BotKind.DESKTOP -> "PC"
        BotKind.HYBRID -> "hybrid phone and PC"
    }
    val availability = when (online) {
        true -> "online"
        false -> "offline"
        null -> "checking"
    }

    Box {
        // No padding on this box: the badge below is placed against its corner, and the whole
        // box — badge included — is what a tap lands on.
        Box(
            modifier = Modifier
                .heightIn(min = TAB_MIN_HEIGHT)
                .widthIn(min = TAB_MIN_WIDTH)
                .combinedClickable(
                    role = Role.Tab,
                    onClick = {
                        winking = true
                        onSelect()
                    },
                    onLongClick = { menuOpen = true },
                )
                // The name is not drawn, so the tab has to say who it is to a screen reader.
                .semantics { contentDescription = "${bot.label}, $kind bot, $availability" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .alpha(if (selected) 1f else 0.65f),
            ) {
                BotAvatar(bot, size = TAB_BLOUB_SIZE, face = face)
            }
            TypeBadge(
                letter = when (bot.kind) {
                    BotKind.LOCAL -> "L"
                    BotKind.DESKTOP -> "D"
                    BotKind.HYBRID -> "C"
                },
                online = online,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Open ${bot.label} chat") }, onClick = { menuOpen = false; onOpenChat() })
            // Only the Chief has a name of its own to change; the others are called what they are.
            if (bot.isHybrid) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
            }
            if (bot.id != BotProfileStore.DEFAULT) {
                DropdownMenuItem(text = { Text("Remove bot") }, onClick = { menuOpen = false; onRemove() })
            }
        }
    }
}

/**
 * The small corner mark on a tab: L for a bot on this phone, D for one on the PC, C for the Chief
 * of Bots. Green when it can answer, red when it cannot, grey until the first check has finished.
 */
@Composable
private fun TypeBadge(letter: String, online: Boolean?, modifier: Modifier = Modifier) {
    val fill = when (online) {
        true -> Color(0xFF3DDC84)
        false -> Color(0xFFFF5252)
        null -> Color(0xFF9E9E9E)
    }
    Box(
        modifier = modifier.size(BADGE_SIZE).clip(CircleShape).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            // Dark on all three fills: it is the one ink that stays readable on each of them.
            color = Color(0xFF0A0A0C),
            // The line box has to be exactly as tall as the glyph and trimmed on both sides.
            // Left to the theme's body style (a 24sp line) the 10sp letter sat inside a box
            // taller than the badge, which is why it came out off-centre.
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                // Letter-spacing is added after every glyph, the last one included, so any of it
                // shifts a lone centred letter off to one side.
                letterSpacing = 0.sp,
                textAlign = TextAlign.Center,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
}

/** A bot's own shape and color, drawn as its avatar, wearing [face]. */
@Composable
private fun BotAvatar(bot: BotChip, size: androidx.compose.ui.unit.Dp, face: BotFace) {
    BloubBot(
        state = face.state,
        size = size,
        shape = bot.shape,
        expression = face.expression,
        ink = Color(bot.color.argb),
        paper = MaterialTheme.colorScheme.background,
        // Each Bloub turns once as it appears — when the screen opens, or a bot is added.
        arrival = true,
        label = null,
    )
}

/** The local hour, kept current so a bot's mood changes as the day does. */
@Composable
private fun rememberHourOfDay(): Int {
    var hour by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(HOUR_CHECK_MS)
            hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        }
    }
    return hour
}

/** The Bloub is the whole tab now, so it is drawn large; its ball is about 0.63 of this. */
private val TAB_BLOUB_SIZE = 64.dp
private val TAB_MIN_HEIGHT = 76.dp
private val TAB_MIN_WIDTH = 80.dp
private val BADGE_SIZE = 18.dp

private const val AVAILABILITY_POLL_MS = 45_000L
private const val HOUR_CHECK_MS = 60_000L
private const val WINK_MS = 1_200L

@Composable
private fun AddBotDialog(
    onDismiss: () -> Unit,
    onAddDesktop: (String) -> Unit,
    onAddLocal: (name: String, systemPrompt: String, isChiefOfBots: Boolean) -> Unit,
) {
    var isLocal by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var botManager by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a bot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isLocal, onClick = { isLocal = true }, label = { Text("Local") })
                    FilterChip(selected = !isLocal, onClick = { isLocal = false }, label = { Text("Desktop (PC)") })
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isLocal) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("System prompt") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = botManager,
                            onCheckedChange = { checked ->
                                botManager = checked
                                // Offer a sensible persona, but never overwrite one the user typed.
                                if (checked && prompt.isBlank()) prompt = BOT_MANAGER_PROMPT
                            },
                        )
                        // A bot of its own that manages the bots on this phone. The Chief of Bots
                        // does that too, as one of its two sides; this is for a dedicated one.
                        Text("Bot manager — can create and remove bots on this phone", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Runs on your phone's own model with this persona — no PC needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Must match a profile that already exists on the PC gateway.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = {
                    if (isLocal) onAddLocal(name, prompt, botManager) else onAddDesktop(name)
                    onDismiss()
                },
                enabled = name.isNotBlank() && (!isLocal || prompt.isNotBlank()),
            ) { Text("Add") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Names the Chief of Bots. It answers to whatever it is called here. */
@Composable
private fun RenameChiefDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name your Chief of Bots") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(ChiefOfBots.MAX_NAME_LENGTH) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "It answers to this name, on the phone and on the PC. Leave it blank to go back to " +
                        "\"${ChiefOfBots.DEFAULT_NAME}\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = { onSave(name); onDismiss() }) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A bot's chat: the thread, and the same composer as the main chat — pill field, `+` menu, mic
 * (tap for hands-free voice chat, long-press to dictate), model, reasoning effort, send and stop.
 *
 * A PC bot takes text alone and has no effort setting to change, so those two are left out for it
 * rather than shown and silently ignored.
 */
@Composable
private fun ChatTab(
    bot: BotChip,
    turns: List<BotTurn>,
    sending: Boolean,
    isListening: Boolean,
    voiceChatActive: Boolean,
    inputPrefill: String,
    reasoningEffort: String,
    pcOnline: Boolean,
    onSend: (text: String, attachmentUri: String?, attachmentMimeType: String?) -> Unit,
    onCancel: () -> Unit,
    onMicToggle: () -> Unit,
    onVoiceChatToggle: () -> Unit,
    onReasoningEffortChange: (String) -> Unit,
    onLeave: () -> Unit,
    threads: List<BotThread>,
    onNewThread: () -> Unit,
    onOpenThread: (String) -> Unit,
    onDeleteThread: (String) -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    // The microphone must not stay open behind another tab.
    DisposableEffect(Unit) { onDispose(onLeave) }

    val listState = rememberLazyListState()
    // Open a restored thread at its newest turn, and follow a reply as it streams.
    LaunchedEffect(turns.size, turns.lastOrNull()?.reply) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        // History and new chat, as the main chat has them.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showHistory = true }, enabled = !sending) {
                Icon(Icons.Outlined.History, contentDescription = "Chat history")
            }
            Text(
                threads.firstOrNull { it.current }?.let(::threadLabel) ?: "Chat",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNewThread, enabled = !sending && turns.isNotEmpty()) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        }
        HorizontalDivider()
        if (showHistory) {
            ThreadHistoryDialog(
                threads = threads,
                onOpen = { showHistory = false; onOpenThread(it) },
                onDelete = onDeleteThread,
                onDismiss = { showHistory = false },
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(turns) { index, turn ->
                TurnCard(turn, waiting = sending && index == turns.lastIndex)
            }
        }

        // Outside the list, so the composer stays put however long the thread grows. Keyed on
        // the bot: what was typed for one bot must not follow you to another.
        key(bot.id) {
            ChatInputBar(
                isSending = sending,
                isListening = isListening,
                onSend = { onSend(it, null, null) },
                onCancel = onCancel,
                onMicToggle = onMicToggle,
                onVoiceChatToggle = onVoiceChatToggle,
                prefillText = inputPrefill,
                voiceChatActive = voiceChatActive,
                // An attachment goes to the phone side, so anything with one is offered it.
                onSendWithAttachment = onSend.takeIf { bot.hasPhone },
                reasoningEffort = reasoningEffort,
                onReasoningEffortChange = onReasoningEffortChange.takeIf { bot.hasPhone },
                // Which brain answers by default: a local bot always answers from this phone's model,
                // whatever the main chat is set to use, and the Chief answers on the PC while it can.
                modelName = when {
                    bot.isLocal -> "On-device"
                    bot.isHybrid && !pcOnline -> "On-device"
                    else -> "PC gateway"
                },
                attachmentsEnabled = bot.hasPhone,
                // Who you are talking to lives in the field; the tab shows only a Bloub.
                placeholder = "Talk to ${bot.label}",
            )
        }
    }
}

/** What a thread is called in the list: what it began with, or a plain name for the bot's first. */
private fun threadLabel(thread: BotThread): String = when {
    !BotThreads.isLater(thread.id) -> "Original chat"
    else -> thread.title.ifBlank { NEW_THREAD_TITLE }
}

@Composable
private fun ThreadHistoryDialog(
    threads: List<BotThread>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    var confirming by remember { mutableStateOf<BotThread?>(null) }
    confirming?.let { thread ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Delete this chat?") },
            text = { Text("“${threadLabel(thread)}” will be removed from this phone. This can't be undone.") },
            confirmButton = { TextButton(onClick = { onDelete(thread.id); confirming = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat history") },
        text = {
            if (threads.isEmpty()) {
                Text("No chats yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(threads, key = { it.id }) { thread ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { onOpen(thread.id) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            ) {
                                Text(
                                    threadLabel(thread),
                                    fontWeight = if (thread.current) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    format.format(java.util.Date(thread.updatedAt)) + if (thread.current) " \u00b7 open now" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { confirming = thread }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete ${threadLabel(thread)}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun JobsTab(jobs: List<RemoteJob>, loading: Boolean, isLocal: Boolean, configured: Boolean, busyJobId: String?, onAction: (RemoteJob, String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isLocal) {
            item {
                Text(
                    "Local bots don't have PC jobs. Schedule on-device runs from the global CRON screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (jobs.isEmpty()) {
            item {
                Text(
                    when {
                        loading -> "Loading…"
                        !configured -> "The PC gateway is not set up yet, so this bot's scheduled jobs can't be loaded."
                        else -> "No scheduled bots on this profile."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(jobs, key = { it.id }) { job ->
            RemoteJobCard(job = job, busy = busyJobId == job.id, onAction = { action -> onAction(job, action) })
        }
    }
}

@Composable
private fun ApprovalsTab(
    isLocal: Boolean,
    drafts: List<RedditDraft>,
    busyCode: String?,
    message: String?,
    onApprove: (RedditDraft, Boolean) -> Unit,
    onSkip: (RedditDraft) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        message?.let { text ->
            item { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (isLocal) {
            item {
                Text(
                    "Nothing is waiting for approval. When this bot wants to run a tool that needs your OK, you'll be asked here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (drafts.isEmpty()) {
            item {
                Text(
                    "No Reddit drafts waiting for approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(if (isLocal) emptyList() else drafts, key = { it.code }) { draft ->
            RedditDraftCard(
                draft = draft,
                busy = busyCode == draft.code,
                onApprove = { useRewrite -> onApprove(draft, useRewrite) },
                onSkip = { onSkip(draft) },
            )
        }
    }
}

@Composable
private fun DashboardTab(entries: List<DashboardEntry>, loading: Boolean) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle("All bots", loading) }
        if (entries.isEmpty() && !loading) {
            item {
                Text(
                    "No bots yet. Tap + to add one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(entries) { entry -> DashboardCard(entry) }
    }
}

@Composable
private fun DashboardCard(entry: DashboardEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.label, fontWeight = FontWeight.SemiBold)
                Text(
                    when (entry.kind) {
                        BotKind.LOCAL -> "Local"
                        BotKind.DESKTOP -> "Desktop"
                        BotKind.HYBRID -> "Hybrid"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(entry.status, style = MaterialTheme.typography.bodyMedium)
            entry.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RedditDraftCard(draft: RedditDraft, busy: Boolean, onApprove: (useRewrite: Boolean) -> Unit, onSkip: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("r/${draft.sub}: ${draft.title}", fontWeight = FontWeight.SemiBold)
            if (draft.score != null) {
                Text(
                    "${draft.critic} score ${draft.score}/10" + (draft.issues?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Draft (${draft.code}):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(draft.text, style = MaterialTheme.typography.bodyMedium)
            draft.rewrite?.let { rewrite ->
                Text(
                    "Rewrite (${draft.code}-R):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(rewrite, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            // Tight enough that Post, Post rewrite and Skip share one row on a phone; if a narrower
            // screen still cannot fit them, the row wraps rather than breaking a label.
            val tight = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onApprove(false) }, enabled = !busy, contentPadding = tight) { Text("Post", maxLines = 1, softWrap = false) }
                if (draft.rewrite != null) {
                    OutlinedButton(onClick = { onApprove(true) }, enabled = !busy, contentPadding = tight) { Text("Post rewrite", maxLines = 1, softWrap = false) }
                }
                OutlinedButton(onClick = onSkip, enabled = !busy, contentPadding = tight) { Text("Skip", maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** One exchange as two chat bubbles, styled like the main chat: you on the right, the bot on the left. */
@Composable
private fun TurnCard(turn: BotTurn, waiting: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // A stored reply with no question before it has an empty `sent`; there is nothing to bubble.
        if (turn.sent.isNotBlank() || turn.attachment) {
            ChatBubble(turn.sent, fromUser = true, attachment = turn.attachment)
        }
        // A turn that was stopped before it answered has no reply to draw; only the turn being
        // waited on shows the placeholder, or a stopped one would read "…" forever.
        if (turn.reply.isNotBlank() || waiting) {
            ChatBubble(turn.reply.ifBlank { "…" }, fromUser = false, failed = turn.failed)
        }
    }
}

@Composable
private fun ChatBubble(text: String, fromUser: Boolean, failed: Boolean = false, attachment: Boolean = false) {
    val bubbleColor = if (fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = when {
        failed -> MaterialTheme.colorScheme.error
        fromUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        // The small corner sits on the speaker's side, as in the main chat.
                        bottomEnd = if (fromUser) 4.dp else 16.dp,
                        bottomStart = if (fromUser) 16.dp else 4.dp,
                    ),
                )
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                if (attachment) {
                    Text(
                        "Image attached",
                        color = textColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = if (text.isNotBlank()) 4.dp else 0.dp),
                    )
                }
                if (text.isNotBlank()) {
                    SelectionContainer {
                        Text(text, color = textColor, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
