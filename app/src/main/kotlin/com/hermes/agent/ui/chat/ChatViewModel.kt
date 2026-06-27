package com.hermes.agent.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.voice.VoiceInputEvent
import com.hermes.agent.data.voice.VoiceInputManager
import com.hermes.agent.data.voice.VoiceOutputEvent
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
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
) : ViewModel() {

    val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    private val _ephemeral = MutableStateFlow(ChatEphemeralState())

    /** Phase 3: text that should prefill the input bar (e.g. from voice). */
    private val _inputPrefill = MutableStateFlow("")

    /** Phase 3: true while voice input is listening. */
    private val _isListening = MutableStateFlow(false)

    private var sendJob: Job? = null
    private var listenJob: Job? = null

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
            )
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
                chatRepository.sendMessageOrchestrated(conversationId, trimmed).collect { event ->
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

    private fun handleOrchestratorEvent(event: OrchestratorEvent) {
        when (event) {
            is OrchestratorEvent.PlanReady -> {
                val summary = PlanSummary(
                    steps = event.plan.steps.map {
                        PlanStepSummary(
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
                    val newSteps = plan.steps.mapIndexed { i, s ->
                        if (i == plan.currentStepIndex) s.copy(status = StepStatus.RUNNING) else s
                    }
                    plan.copy(steps = newSteps)
                }
                _ephemeral.value = _ephemeral.value.copy(plan = updated)
            }
            is OrchestratorEvent.StepFinished -> {
                val updated = _ephemeral.value.plan?.let { plan ->
                    val idx = plan.currentStepIndex
                    val newSteps = plan.steps.mapIndexed { i, s ->
                        when {
                            i == idx -> s.copy(status = if (event.success) StepStatus.SUCCEEDED else StepStatus.FAILED)
                            else -> s
                        }
                    }
                    plan.copy(steps = newSteps, currentStepIndex = (idx + 1).coerceAtMost(plan.steps.lastIndex))
                }
                _ephemeral.value = _ephemeral.value.copy(plan = updated)
            }
            is OrchestratorEvent.ToolCallRequested -> {
                val summary = ToolCallSummary(
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
                    if (it.name == event.call.name && it.status == ToolCallStatus.RUNNING) {
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
                _ephemeral.value = ChatEphemeralState()
                // Phase 3: speak the reply via TTS.
                speakReply(event.finalText)
            }
            is OrchestratorEvent.Failed -> {
                Timber.tag("ChatVM").w("orchestration failed: %s", event.message)
                _ephemeral.value = ChatEphemeralState(
                    errorMessage = event.message,
                )
            }
            is OrchestratorEvent.StateChanged -> { /* no-op */ }
        }
    }

    fun cancel() {
        sendJob?.cancel()
        sendJob = null
        _ephemeral.value = ChatEphemeralState()
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
        voiceOutputManager.shutdown()
    }
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
)
