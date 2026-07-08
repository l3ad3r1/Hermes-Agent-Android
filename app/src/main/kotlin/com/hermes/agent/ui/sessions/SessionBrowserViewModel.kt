package com.hermes.agent.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.local.entity.SessionWithMessageCount
import com.hermes.agent.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SessionBrowserUiState {
    object Loading : SessionBrowserUiState()
    data class Success(
        val sessions: List<SessionWithMessageCount>,
    ) : SessionBrowserUiState()
    data class Error(val message: String) : SessionBrowserUiState()
}

@HiltViewModel
class SessionBrowserViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<SessionBrowserUiState>(SessionBrowserUiState.Loading)
    val uiState: StateFlow<SessionBrowserUiState> = _uiState.asStateFlow()
    
    init {
        browseRecent()
    }
    
    /**
     * Browse recent sessions (FTS5 browse shape).
     */
    fun browseRecent() {
        viewModelScope.launch {
            try {
                _uiState.value = SessionBrowserUiState.Loading
                val sessions = sessionRepository.getRecent(limit = 50)
                val sessionsWithCounts = sessions.map { session ->
                    val messageCount = sessionRepository.getMessageCount(session.id)
                    SessionWithMessageCount(session, messageCount)
                }
                _uiState.value = SessionBrowserUiState.Success(sessionsWithCounts)
            } catch (e: Exception) {
                _uiState.value = SessionBrowserUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Search sessions by query (FTS5 discovery shape).
     */
    fun searchSessions(query: String) {
        viewModelScope.launch {
            try {
                _uiState.value = SessionBrowserUiState.Loading
                if (query.isBlank()) {
                    browseRecent()
                    return@launch
                }
                
                val results = sessionRepository.searchByQuery(query, limit = 50)
                val sessions = results.map { session ->
                    val messageCount = sessionRepository.getMessageCount(session.id)
                    SessionWithMessageCount(session, messageCount)
                }
                _uiState.value = SessionBrowserUiState.Success(sessions)
            } catch (e: Exception) {
                _uiState.value = SessionBrowserUiState.Error(e.message ?: "Search failed")
            }
        }
    }
    
    /**
     * Delete a session.
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                sessionRepository.deleteSession(sessionId)
                // Refresh the list
                browseRecent()
            } catch (e: Exception) {
                _uiState.value = SessionBrowserUiState.Error(e.message ?: "Delete failed")
            }
        }
    }
}