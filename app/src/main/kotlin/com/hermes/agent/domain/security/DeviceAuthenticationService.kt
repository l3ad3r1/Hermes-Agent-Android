package com.hermes.agent.domain.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceAuthenticationRequest(
    val id: String,
    val title: String,
    val reason: String,
)

/** Bridges execution code to the foreground Android biometric/device-credential UI. */
@Singleton
class DeviceAuthenticationService @Inject constructor() {
    private val requestMutex = Mutex()
    private var pendingDeferred: CompletableDeferred<Boolean>? = null
    private val _pendingRequest = MutableStateFlow<DeviceAuthenticationRequest?>(null)
    val pendingRequest: StateFlow<DeviceAuthenticationRequest?> = _pendingRequest

    suspend fun authenticate(title: String, reason: String): Boolean = requestMutex.withLock {
        val request = DeviceAuthenticationRequest(UUID.randomUUID().toString(), title, reason)
        val deferred = CompletableDeferred<Boolean>()
        pendingDeferred = deferred
        _pendingRequest.value = request
        try {
            withTimeoutOrNull(AUTHENTICATION_TIMEOUT_MS) { deferred.await() } ?: false
        } finally {
            _pendingRequest.value = null
            pendingDeferred = null
        }
    }

    fun submit(requestId: String, authenticated: Boolean) {
        if (_pendingRequest.value?.id == requestId) pendingDeferred?.complete(authenticated)
    }

    companion object {
        const val AUTHENTICATION_TIMEOUT_MS = 60_000L
    }
}
