package com.hermes.agent.ui.chat
import com.hermes.agent.domain.llm.*

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.agent.ClarificationBus
import com.hermes.agent.data.agent.TodoStore
import com.hermes.agent.data.voice.VoiceInputEvent
import com.hermes.agent.data.voice.VoiceInputManager
import com.hermes.agent.data.voice.VoiceOutputEvent
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Chat ViewModel.
 *
 * Supports voice input via [VoiceInputManager] and voice output via
 * [VoiceOutputManager].
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val conversationRepository: ConversationRepository,
    private val chatRepository: ChatRepository,
    private val voiceInputManager: VoiceInputManager,
    private val voiceOutputManager: VoiceOutputManager,
    private val clarificationBus: ClarificationBus,
    private val todoStore: TodoStore,
    private val settingsRepository: SettingsRepository,
    private val toolConfirmationService: com.hermes.agent.domain.tool.ToolConfirmationService,
    private val executionPlanRepository: ExecutionPlanRepository,
    private val ultraSkillInterceptor: com.hermes.agent.domain.agent.UltraSkillInterceptor,
) : ViewModel() {

    val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    val pendingToolConfirmation = toolConfirmationService.pendingRequest

    fun submitToolConfirmation(requestId: String, approved: Boolean) {
        toolConfirmationService.submitConfirmation(requestId, approved)
    }

    private val _ephemeral = MutableStateFlow(ChatEphemeralState())

    /** Phase 3: text that should prefill the input bar (e.g. from voice). */
    private val _inputPrefill = MutableStateFlow("")

    /** Phase 3: true while voice input is listening. */
    private val _isListening = MutableStateFlow(false)

    /** True while the hands-free listen/speak loop is running. */
    private val _voiceChatActive = MutableStateFlow(false)

    private var sendJob: Job? = null
    private var listenJob: Job? = null
    private var speakJob: Job? = null

    /** Consecutive voice-chat turns that yielded no usable transcript. */
    private var emptyVoiceTurns = 0

    init {
        // Mirror the agent's pending `clarify` question into UI state so the
        // chat screen can render it and collect the user's answer.
        viewModelScope.launch {
            clarificationBus.pending.collect { req ->
                _ephemeral.value = _ephemeral.value.copy(
                    pendingClarification = req?.let {
                        ClarificationRequest(it.question, it.choices)
                    },
                )
            }
        }
    }

    val uiState: StateFlow<ChatUiState> =
        combine(
            conversationRepository.observeMessages(conversationId),
            conversationRepository.observeConversation(conversationId),
            _ephemeral,
            _inputPrefill,
            _isListening,
        ) { messages, conversation, ephemeral, prefill, isListening ->
            ChatUiState(
                messages = messages,
                streamingText = ephemeral.streamingText,
                streamingIsOnDevice = ephemeral.streamingIsOnDevice,
                streamingAgentRole = ephemeral.streamingAgentRole,
                isSending = ephemeral.isSending,
                errorMessage = ephemeral.errorMessage,
                notice = ephemeral.notice,
                title = conversation?.title ?: "New conversation",
                currentPlan = ephemeral.plan,
                toolCalls = ephemeral.toolCalls,
                inputPrefill = prefill,
                isListening = isListening,
                estimatedTokens = messages.sumOf { it.content.length } / 4,
                activeModel = ephemeral.activeModel,
                isOnDevice = ephemeral.streamingIsOnDevice,
                pendingClarification = ephemeral.pendingClarification,
            )
        }.combine(executionPlanRepository.observeLatest(conversationId)) { state, persistedPlan ->
            // Room is the source of truth once a plan has been persisted. The
            // ephemeral event copy remains a fallback for tests/legacy flows.
            state.copy(currentPlan = persistedPlan?.toSummary() ?: state.currentPlan)
        }.combine(todoStore.items) { state, todos ->
            // The todo plan persists across turns (it survives _ephemeral
            // resets), so it's merged in from its own store here.
            state.copy(todos = todos.map { TodoItem(it.id, it.content, it.status) })
        }.combine(settingsRepository.observe()) { state, settings ->
            state.copy(showToolCalls = settings.showToolCalls)
        }.combine(_voiceChatActive) { state, active ->
            // Chained rather than folded into the combine above: the typed
            // overloads stop at five flows, and a sixth silently drops to the
            // untyped vararg form.
            state.copy(voiceChatActive = active)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatUiState(),
        )

    val state: StateFlow<ChatUiState> get() = uiState

    /**
     * Edit a turn in place.
     *
     * The original turn is removed first. Prefilling the composer alone left
     * the old message sitting above its own replacement, so an edited turn
     * appeared twice and the model saw both — an edit is meant to change a
     * message, not add one.
     */
    fun editMessage(message: Message) {
        viewModelScope.launch {
            runCatching { conversationRepository.rewindTo(conversationId, message) }
                .onFailure { Timber.tag("Chat").w(it, "could not clear the turn being edited") }
            _inputPrefill.value = message.content
        }
    }

    /**
     * Rewind: drop this message and everything after it. Destructive by
     * definition, so the UI confirms first; the text is handed back to the
     * composer so the turn can be retried without retyping it.
     */
    fun rewindTo(message: Message) {
        viewModelScope.launch {
            runCatching { conversationRepository.rewindTo(conversationId, message) }
                .onSuccess { removed ->
                    if (message.role == MessageRole.USER) _inputPrefill.value = message.content
                    _ephemeral.value = _ephemeral.value.copy(
                        errorMessage = null,
                        notice = "Rewound $removed message${if (removed == 1) "" else "s"}",
                    )
                }
                .onFailure { t ->
                    Timber.tag("Chat").w(t, "rewind failed")
                    _ephemeral.value = _ephemeral.value.copy(errorMessage = "Could not rewind this chat.")
                }
        }
    }

    /**
     * Fork: copy the transcript up to this message into a new conversation and
     * hand back its id so the caller can navigate there. Non-destructive, which
     * is what makes it the safe counterpart to rewind.
     */
    fun forkFrom(message: Message, onForked: (String) -> Unit) {
        viewModelScope.launch {
            val title = message.content.take(40).ifBlank { "Forked chat" }
            runCatching { conversationRepository.forkFrom(conversationId, message, title) }
                .onSuccess(onForked)
                .onFailure { t ->
                    Timber.tag("Chat").w(t, "fork failed")
                    _ephemeral.value = _ephemeral.value.copy(errorMessage = "Could not fork this chat.")
                }
        }
    }

    /**
     * Re-run a turn against a different model.
     *
     * Like [editMessage], this replaces the turn rather than appending one:
     * retrying used to leave "Update to high" and "[ultrabrain] Update to high"
     * stacked in the transcript, which reads as the user asking twice.
     */
    fun retryWithAlias(message: Message, alias: String) {
        val cleanContent = message.content
            .removePrefix("[ultrabrain] ")
            .removePrefix("[quick] ")
            .trim()
        viewModelScope.launch {
            runCatching { conversationRepository.rewindTo(conversationId, message) }
                .onFailure { Timber.tag("Chat").w(it, "could not clear the turn being retried") }
            sendMessage("[$alias] $cleanContent")
        }
    }

    fun sendMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || _ephemeral.value.isSending) return

        sendJob?.cancel()
        _ephemeral.value = ChatEphemeralState(
            streamingText = "",
            streamingIsOnDevice = true,
            isSending = true,
            errorMessage = null,
        )
        _inputPrefill.value = ""

        sendJob = viewModelScope.launch {
            try {
                if (ultraSkillInterceptor.intercept(conversationId, trimmed)) {
                    _ephemeral.value = ChatEphemeralState()
                    return@launch
                }
                
                chatRepository.sendMessageOrchestrated(conversationId, trimmed, ExecutionOrigin.INTERACTIVE).collect { event ->
                    handleOrchestratorEvent(event)
                }
            } catch (cancelled: CancellationException) {
                // Stopping a reply, or sending the next one while this is still
                // streaming, cancels this job. Swallowing that here would
                // overwrite the state [cancel] just cleared and show the user
                // "StandaloneCoroutine was cancelled" as if the turn failed.
                throw cancelled
            } catch (t: Throwable) {
                Timber.tag("ChatVM").w(t, "sendMessageOrchestrated failed")
                _ephemeral.value = _ephemeral.value.copy(
                    isSending = false,
                    errorMessage = t.message ?: "Unknown error",
                )
            }
        }
    }

    private fun handleOrchestratorEvent(event: OrchestratorEvent) {
        when (event) {
            is OrchestratorEvent.PlanReady -> {
                val summary = PlanSummary(
                    steps = event.plan.steps.map {
                        PlanStepSummary(
                            id = it.id,
                            description = it.description,
                            agentRole = it.agentRole,
                            status = StepStatus.PENDING,
                        )
                    },
                    currentStepIndex = 0,
                )
                _ephemeral.value = _ephemeral.value.copy(plan = summary)
            }
            is OrchestratorEvent.StepStarted -> {
                val updated = _ephemeral.value.plan?.let { plan ->
                    val activeIndex = plan.steps.indexOfFirst { it.id == event.stepId }
                    val newSteps = plan.steps.map { s ->
                        if (s.id == event.stepId) s.copy(status = StepStatus.RUNNING) else s
                    }
                    plan.copy(
                        steps = newSteps,
                        currentStepIndex = activeIndex.takeIf { it >= 0 } ?: plan.currentStepIndex,
                    )
                }
                _ephemeral.value = _ephemeral.value.copy(plan = updated)
            }
            is OrchestratorEvent.StepFinished -> {
                val updated = _ephemeral.value.plan?.let { plan ->
                    val idx = plan.steps.indexOfFirst { it.id == event.stepId }
                    val newSteps = plan.steps.map { s ->
                        if (s.id == event.stepId) {
                            s.copy(status = if (event.success) StepStatus.SUCCEEDED else StepStatus.FAILED)
                        } else {
                            s
                        }
                    }
                    val nextIndex = if (idx >= 0) {
                        (idx + 1).coerceAtMost(plan.steps.lastIndex)
                    } else {
                        plan.currentStepIndex
                    }
                    plan.copy(steps = newSteps, currentStepIndex = nextIndex)
                }
                _ephemeral.value = _ephemeral.value.copy(plan = updated)
            }
            is OrchestratorEvent.ToolCallRequested -> {
                val summary = ToolCallSummary(
                    callId = event.call.id,
                    name = event.call.name,
                    argumentsPreview = event.call.arguments.entries.joinToString { "${it.key}=${it.value}" },
                    status = ToolCallStatus.RUNNING,
                    outputPreview = null,
                )
                _ephemeral.value = _ephemeral.value.copy(
                    toolCalls = _ephemeral.value.toolCalls + summary,
                )
            }
            is OrchestratorEvent.ToolCallResult -> {
                val updated = _ephemeral.value.toolCalls.map {
                    if (it.callId == event.call.id && it.status == ToolCallStatus.RUNNING) {
                        it.copy(
                            status = if (event.success) ToolCallStatus.SUCCEEDED else ToolCallStatus.FAILED,
                            outputPreview = event.output.take(200),
                        )
                    } else it
                }
                _ephemeral.value = _ephemeral.value.copy(toolCalls = updated)
            }
            is OrchestratorEvent.ReplyToken -> {
                val acc = _ephemeral.value.streamingText.orEmpty() + event.text
                _ephemeral.value = _ephemeral.value.copy(streamingText = acc)
            }
            is OrchestratorEvent.ReplyComplete -> {
                // If the agent used the `speak` tool this turn it already chose
                // what to say aloud, so reading the reply on top of it would say
                // everything twice.
                val alreadySpoke = _ephemeral.value.toolCalls.any { it.name == "speak" }
                _ephemeral.value = ChatEphemeralState()

                // Replies are only read aloud in voice chat. Outside it the app
                // stays silent — a typed conversation should not start talking.
                if (_voiceChatActive.value) {
                    if (alreadySpoke) listenForNextTurn() else speakThenListen(event.finalText)
                }
            }
            is OrchestratorEvent.Failed -> {
                Timber.tag("ChatVM").w("orchestration failed: %s", event.message)
                _ephemeral.value = ChatEphemeralState(
                    errorMessage = event.message,
                )
                // Hand the turn back rather than leaving voice chat dead after a
                // failed reply.
                if (_voiceChatActive.value) listenForNextTurn()
            }
            is OrchestratorEvent.StateChanged -> { /* no-op */ }
        }
    }

    fun cancel() {
        clarificationBus.cancel()
        sendJob?.cancel()
        sendJob = null
        _ephemeral.value = ChatEphemeralState()
    }

    /** Answer the agent's pending `clarify` question, resuming the tool. */
    fun answerClarification(answer: String) {
        val trimmed = answer.trim()
        if (trimmed.isEmpty()) return
        clarificationBus.answer(trimmed)
    }

    fun dismissError() {
        _ephemeral.value = _ephemeral.value.copy(errorMessage = null)
    }

    fun dismissNotice() {
        _ephemeral.value = _ephemeral.value.copy(notice = null)
    }

    fun renameConversation(newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            conversationRepository.renameConversation(conversationId, trimmed)
        }
    }

    // --- Phase 3: Voice I/O ---

    /** Dictation: fills the input bar, leaving the user to send. */
    fun toggleVoiceInput() {
        if (_isListening.value) {
            stopVoiceInput()
        } else {
            startVoiceInput()
        }
    }

    /**
     * Hands-free voice chat: speak, and Hermes answers aloud and listens again.
     *
     * The turn is a strict cycle — listen, send, speak, listen — never two of
     * those at once. Overlapping them means the recogniser hears the reply
     * coming out of the speaker and answers Hermes instead of the user.
     */
    fun toggleVoiceChat() {
        if (_voiceChatActive.value) stopVoiceChat() else startVoiceChat()
    }

    private fun startVoiceChat() {
        if (!voiceInputManager.isAvailable()) {
            _ephemeral.value = _ephemeral.value.copy(
                errorMessage = "Speech recognition not available on this device",
            )
            return
        }
        _voiceChatActive.value = true
        emptyVoiceTurns = 0
        // Warm the engine now: the first speak() otherwise arrives before the
        // engine is ready and is dropped, so the first reply is silent and the
        // loop never gets its Done to listen on.
        voiceOutputManager.initialize()
        startVoiceInput()
    }

    private fun stopVoiceChat() {
        _voiceChatActive.value = false
        speakJob?.cancel()
        speakJob = null
        voiceOutputManager.stop()
        stopVoiceInput()
    }

    /**
     * Listen again for the user's next turn, if voice chat is still on.
     *
     * Gives up after [MAX_EMPTY_VOICE_TURNS] turns that produced nothing. The
     * loop restarts the recogniser the instant it finishes, so a recogniser
     * that fails immediately — no microphone permission, no recognition
     * service — would otherwise spin as fast as the CPU allows, forever.
     */
    private fun listenForNextTurn() {
        if (!_voiceChatActive.value) return
        if (emptyVoiceTurns >= MAX_EMPTY_VOICE_TURNS) {
            stopVoiceChat()
            _ephemeral.value = _ephemeral.value.copy(
                errorMessage = "Voice chat stopped — I couldn't hear anything.",
            )
            return
        }
        startVoiceInput()
    }

    /**
     * Read [text] aloud, then hand the turn back to the microphone.
     *
     * Listening only resumes once the engine reports the utterance finished.
     * Starting the recogniser earlier would capture Hermes's own voice.
     */
    private fun speakThenListen(text: String) {
        if (text.isBlank()) {
            listenForNextTurn()
            return
        }
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            if (!voiceOutputManager.isAvailable()) {
                // No engine: skip the speaking half rather than stall the loop.
                listenForNextTurn()
                return@launch
            }
            voiceOutputManager.speak(text).collect { event ->
                when (event) {
                    // Both terminal states hand the turn back — a TTS failure
                    // should not silently end the conversation.
                    VoiceOutputEvent.Done -> listenForNextTurn()
                    is VoiceOutputEvent.Error -> listenForNextTurn()
                    VoiceOutputEvent.Start -> Unit
                }
            }
        }
    }

    private fun startVoiceInput() {
        if (!voiceInputManager.isAvailable()) {
            _ephemeral.value = _ephemeral.value.copy(
                errorMessage = "Speech recognition not available on this device",
            )
            return
        }
        listenJob?.cancel()
        _isListening.value = true
        listenJob = viewModelScope.launch {
            voiceInputManager.listen().collect { event ->
                when (event) {
                    is VoiceInputEvent.Partial -> _inputPrefill.value = event.text
                    is VoiceInputEvent.Final -> {
                        _isListening.value = false
                        if (_voiceChatActive.value) {
                            // Hands-free: send it rather than parking it in the
                            // input bar for a tap that will never come.
                            _inputPrefill.value = ""
                            if (event.text.isNotBlank()) {
                                emptyVoiceTurns = 0
                                sendMessage(event.text)
                            } else {
                                emptyVoiceTurns++
                                listenForNextTurn()
                            }
                        } else {
                            _inputPrefill.value = event.text
                        }
                    }
                    is VoiceInputEvent.Error -> {
                        _isListening.value = false
                        // Recogniser errors are routine in a hands-free loop —
                        // silence times out. Surfacing them would bury the chat
                        // in snackbars, so just take the turn again.
                        if (_voiceChatActive.value) {
                            emptyVoiceTurns++
                            listenForNextTurn()
                        } else {
                            _ephemeral.value = _ephemeral.value.copy(errorMessage = event.message)
                        }
                    }
                    VoiceInputEvent.Ready -> { /* no-op */ }
                }
            }
        }
    }

    private fun stopVoiceInput() {
        listenJob?.cancel()
        listenJob = null
        _isListening.value = false
    }

    fun stopSpeech() {
        voiceOutputManager.stop()
    }

    private companion object {
        /** Turns of silence or recogniser failure before voice chat gives up. */
        const val MAX_EMPTY_VOICE_TURNS = 3
    }

    override fun onCleared() {
        super.onCleared()
        sendJob?.cancel()
        listenJob?.cancel()
        speakJob?.cancel()
        voiceOutputManager.stop()
        
        // Trigger summarization when the chat session ends. Plain call, not
        // viewModelScope.launch: onCleared() runs after viewModelScope is
        // already cancelled, so a coroutine launched here would never run.
        // The repository does the work on its own singleton scope.
        chatRepository.summarizeConversation(conversationId)
    }
}

private fun com.hermes.agent.domain.model.ExecutionPlan.toSummary(): PlanSummary {
    val summaries = steps.map { step ->
        PlanStepSummary(
            id = step.id,
            description = step.description,
            agentRole = step.agentRole,
            status = StepStatus.valueOf(step.status.name),
        )
    }
    val currentIndex = summaries.indexOfFirst { it.status == StepStatus.RUNNING }
        .takeIf { it >= 0 }
        ?: summaries.indexOfFirst { it.status == StepStatus.PENDING }.takeIf { it >= 0 }
        ?: summaries.lastIndex.coerceAtLeast(0)
    return PlanSummary(summaries, currentIndex)
}

private data class ChatEphemeralState(
    val streamingText: String? = null,
    val streamingIsOnDevice: Boolean = true,
    val streamingAgentRole: com.hermes.agent.domain.model.AgentRole? = null,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null,
    val plan: PlanSummary? = null,
    val toolCalls: List<ToolCallSummary> = emptyList(),
    val activeModel: String = "",
    val pendingClarification: ClarificationRequest? = null,
)
