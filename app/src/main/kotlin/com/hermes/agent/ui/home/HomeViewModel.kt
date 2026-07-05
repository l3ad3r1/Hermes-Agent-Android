package com.hermes.agent.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.memory.UserModelService
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.service.AgentServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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

    /**
     * Hermes's context-aware presence: name-aware time-of-day greeting,
     * a status line (busy ticket wins over idle personality lines), and
     * the eye mood. Reactive to new memories (Hermes learning your name
     * updates the greeting live) and to the background agent's activity.
     */
    val presence: StateFlow<HermesPersona.Presence> =
        combine(
            memoryRepository.observeMemories(),
            AgentServiceController.currentTask,
            AgentServiceController.running,
            minuteTicker,
        ) { memories, busyTask, running, _ ->
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
                busyTask = if (running) busyTask else null,
                seed = (cal.get(Calendar.DAY_OF_YEAR) * 24 + hour).toLong(),
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HermesPersona.compose(
                name = null,
                hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                busyTask = null,
            ),
        )

    fun createNewConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            onCreated(conversationRepository.createConversation())
        }
    }
}
