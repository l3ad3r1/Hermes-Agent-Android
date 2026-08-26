package com.hermes.agent.data.oauth

import android.net.Uri
import com.hermes.agent.domain.oauth.OAuthSession
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class OAuthCallbackEvent {
    data class Success(val session: OAuthSession, val code: String) : OAuthCallbackEvent()
    data class Error(val session: OAuthSession?, val error: String) : OAuthCallbackEvent()
}

@Singleton
class OAuthCallbackReceiver @Inject constructor() {

    private val _events = MutableSharedFlow<OAuthCallbackEvent>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<OAuthCallbackEvent> = _events.asSharedFlow()

    private var pendingSession: OAuthSession? = null

    @Synchronized
    fun registerPendingSession(session: OAuthSession) {
        this.pendingSession = session
    }

    @Synchronized
    fun handleCallback(uri: Uri?) {
        if (uri == null) {
            _events.tryEmit(OAuthCallbackEvent.Error(pendingSession, "Empty callback URI"))
            return
        }

        val error = uri.getQueryParameter("error") ?: uri.getQueryParameter("error_description")
        if (!error.isNullOrBlank()) {
            _events.tryEmit(OAuthCallbackEvent.Error(pendingSession, error))
            pendingSession = null
            return
        }

        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            _events.tryEmit(OAuthCallbackEvent.Error(pendingSession, "No authorization code found in callback"))
            pendingSession = null
            return
        }

        val returnedState = uri.getQueryParameter("state")
        val session = pendingSession
        if (session != null && !returnedState.isNullOrBlank() && returnedState != session.state) {
            _events.tryEmit(OAuthCallbackEvent.Error(session, "State mismatch (possible CSRF attack)"))
            pendingSession = null
            return
        }

        if (session != null) {
            _events.tryEmit(OAuthCallbackEvent.Success(session, code))
        } else {
            _events.tryEmit(OAuthCallbackEvent.Error(null, "No matching OAuth session found"))
        }

        pendingSession = null
    }
}
