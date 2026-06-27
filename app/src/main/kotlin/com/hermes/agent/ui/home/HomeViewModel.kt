package com.hermes.agent.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val settings: SettingsRepository,
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

    fun createNewConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            onCreated(conversationRepository.createConversation())
        }
    }
}
