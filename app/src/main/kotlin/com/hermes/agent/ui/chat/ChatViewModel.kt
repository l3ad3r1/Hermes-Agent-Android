package com.hermes.agent.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.agent.ClarificationBus
import com.hermes.agent.data.agent.TodoStore
import com.hermes.agent.data.voice.VoiceInputEvent
import com.hermes.agent.data.voice.VoiceInputManager
import com.hermes.agent.data.voice.VoiceOutputEvent
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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

    private var sendJob: Job? = null
    private var listenJob: Job? = null

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
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatUiState(),
        )

    val state: StateFlow<ChatUiState> get() = uiState

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
                chatRepository.sendMessageOrchestrated(conversationId, trimmed, ExecutionOrigin.INTERACTIVE).collect { event ->
                    handleOrchestratorEvent(event)
                }
            } catch (t: Throwable) {
                Timber.tag("ChatVM").w(t, "sendMessageOrchestrated failed")
                _ephemeral.value = _ephemeral.value.copy(
                    isSending = false,
                    errorMessage = t.message ?: "Unknown error",
                )
            }
        }
    }

    private var spokenTextLength = 0
    private val sentenceRegex = Regex("(?<=[.!?])\\s+")

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

                // If voice is active and we have new complete sentences, speak them
                // immediately. Offsets are tracked against the REAL accumulated
                // string: rebuilding the text with joinToString(" ") replaced the
                // original separators (newlines, double spaces), so the byte count
                // drifted and later substrings repeated or swallowed words.
                val alreadySpoke = _ephemeral.value.toolCalls.any { it.name == "speak" }
                if (!alreadySpoke && voiceOutputManager.isAvailable()) {
                    val unreadText = acc.substring(spokenTextLength)
                    val lastBoundary = sentenceRegex.findAll(unreadText).lastOrNull()
                    if (lastBoundary != null) {
                        val completeEnd = lastBoundary.range.last + 1
                        val toSpeak = unreadText.substring(0, completeEnd).trim()
                        if (toSpeak.isNotBlank()) speakReply(toSpeak)
                        spokenTextLength += completeEnd
                    }
                }
            }
            is OrchestratorEvent.ReplyComplete -> {
                // If the agent already used the `speak` tool this turn, it has
                // chosen exactly what to say aloud — don't auto-read the reply
                // on top of it (that caused the text to be spoken twice).
                val alreadySpoke = _ephemeral.value.toolCalls.any { it.name == "speak" }
                _ephemeral.value = ChatEphemeralState()
                
                if (!alreadySpoke) {
                    val unreadText = event.finalText.substring(spokenTextLength.coerceAtMost(event.finalText.length))
                    if (unreadText.isNotBlank()) {
                        speakReply(unreadText)
                    }
                }
                spokenTextLength = 0
            }
            is OrchestratorEvent.Failed -> {
                Timber.tag("ChatVM").w("orchestration failed: %s", event.message)
                _ephemeral.value = ChatEphemeralState(
                    errorMessage = event.message,
                )
                spokenTextLength = 0
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

    fun renameConversation(newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            conversationRepository.renameConversation(conversationId, trimmed)
        }
    }

    // --- Phase 3: Voice I/O ---

    fun toggleVoiceInput() {
        if (_isListening.value) {
            stopVoiceInput()
        } else {
            startVoiceInput()
        }
    }

    private fun startVoiceInput() {
        if (!voiceInputManager.isAvailable()) {
            _ephemeral.value = _ephemeral.value.copy(
                errorMessage = "Speech recognition not available on this device",
            )
            return
        }
        _isListening.value = true
        listenJob = viewModelScope.launch {
            voiceInputManager.listen().collect { event ->
                when (event) {
                    is VoiceInputEvent.Partial -> _inputPrefill.value = event.text
                    is VoiceInputEvent.Final -> {
                        _inputPrefill.value = event.text
                        _isListening.value = false
                    }
                    is VoiceInputEvent.Error -> {
                        _ephemeral.value = _ephemeral.value.copy(errorMessage = event.message)
                        _isListening.value = false
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

    private fun speakReply(text: String) {
        if (text.isBlank()) return
        voiceOutputManager.initialize { ready ->
            if (ready) {
                viewModelScope.launch {
                    voiceOutputManager.speak(text).collect { /* UI could track playing state here */ }
                }
            }
        }
    }

    fun stopSpeech() {
        voiceOutputManager.stop()
    }

    override fun onCleared() {
        super.onCleared()
        sendJob?.cancel()
        listenJob?.cancel()
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
    val plan: PlanSummary? = null,
    val toolCalls: List<ToolCallSummary> = emptyList(),
    val activeModel: String = "",
    val pendingClarification: ClarificationRequest? = null,
)
