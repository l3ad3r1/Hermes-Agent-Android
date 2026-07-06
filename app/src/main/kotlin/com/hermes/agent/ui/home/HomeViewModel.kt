package com.hermes.agent.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.memory.UserModelService
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.voice.VoiceActivity
import com.hermes.agent.domain.agent.AgentActivity
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.service.AgentServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val settings: SettingsRepository,
    memoryRepository: MemoryRepository,
) : ViewModel() {

    /** Most recent conversations for the dashboard's "Recent threads". */
    val recentThreads: StateFlow<List<Conversation>> =
        conversationRepository.observeConversations()
            .map { it.take(3) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Currently-configured cloud model, surfaced on the gateway card. */
    val modelName: StateFlow<String> =
        settings.observe()
            .map { it.cloudModel.ifBlank { "not configured" } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Re-evaluates the greeting once a minute so hour transitions land. */
    private val minuteTicker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    /** A transient reaction overlaid on the contextual presence (tap or a
     *  celebrated ticket completion). Null when nothing is reacting. */
    private sealed interface Reaction {
        val seed: Long
        data class Poke(override val seed: Long) : Reaction
        data class Celebrate(val task: String, override val seed: Long) : Reaction
    }

    private val reaction = MutableStateFlow<Reaction?>(null)
    private var reactionCount = 0L
    private var reactionJob: Job? = null

    init {
        // A finished background ticket → celebratory bounce.
        viewModelScope.launch {
            AgentServiceController.taskCompleted.collect { title ->
                fireReaction(Reaction.Celebrate(title, seed = ++reactionCount), CELEBRATE_REACTION_MS)
            }
        }
    }

    /**
     * Hermes's context-aware presence. Base priority: listening (mic hot) >
     * busy ticket > thinking > time-of-day. Transient reactions (poke,
     * celebrate) are overlaid on top. Reactive to memories (Hermes learning
     * your name updates the greeting live), the background agent's activity,
     * live orchestrator runs (THINKING), voice capture (LISTENING), taps,
     * and ticket completions.
     */
    val presence: StateFlow<HermesPersona.Presence> =
        combine(
            memoryRepository.observeMemories(),
            AgentServiceController.currentTask,
            AgentActivity.thinking,
            VoiceActivity.listening,
            minuteTicker,
        ) { memories, busyTask, thinking, listening, _ ->
            val contents = memories.map { it.content }
            val userModel = contents
                .firstOrNull { it.startsWith(UserModelService.MODEL_PREFIX) }
                ?.removePrefix(UserModelService.MODEL_PREFIX)
            val name = HermesPersona.extractName(
                memories = contents.filterNot { it.startsWith(UserModelService.MODEL_PREFIX) },
                userModel = userModel,
            )
            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            HermesPersona.compose(
                name = name,
                hourOfDay = hour,
                busyTask = if (AgentServiceController.running.value) busyTask else null,
                isThinking = thinking,
                isListening = listening,
                seed = (cal.get(Calendar.DAY_OF_YEAR) * 24 + hour).toLong(),
            )
        }.combine(reaction) { base, r ->
            when (r) {
                is Reaction.Poke -> HermesPersona.pokeReaction(base, r.seed)
                is Reaction.Celebrate -> HermesPersona.celebrateReaction(base, r.task, r.seed)
                null -> base
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HermesPersona.compose(
                name = null,
                hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                busyTask = null,
            ),
        )

    /** Tap on the eyes: startled reaction + quip for a few seconds. */
    fun poke() {
        fireReaction(Reaction.Poke(seed = ++reactionCount), POKE_REACTION_MS)
    }

    private fun fireReaction(r: Reaction, durationMs: Long) {
        reaction.value = r
        reactionJob?.cancel()
        reactionJob = viewModelScope.launch {
            delay(durationMs)
            // Only clear if still showing this reaction (avoid racing a newer one).
            if (reaction.value === r) reaction.value = null
        }
    }

    fun createNewConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            onCreated(conversationRepository.createConversation())
        }
    }

    private companion object {
        const val POKE_REACTION_MS = 3_000L
        const val CELEBRATE_REACTION_MS = 4_000L
    }
}
