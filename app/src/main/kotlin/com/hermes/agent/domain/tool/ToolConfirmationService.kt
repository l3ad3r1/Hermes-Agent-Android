package com.hermes.agent.domain.tool

import com.hermes.agent.data.llm.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One tool call waiting for a human verdict, addressed by a unique id. */
data class PendingConfirmation(
    val id: String,
    val call: ToolCall,
)

@Singleton
class ToolConfirmationService @Inject constructor() {
    private val requestMutex = Mutex()
    private var pendingDeferred: CompletableDeferred<Boolean>? = null

    private val _pendingRequest = MutableStateFlow<PendingConfirmation?>(null)
    val pendingRequest: StateFlow<PendingConfirmation?> = _pendingRequest

    suspend fun awaitConfirmation(call: ToolCall): Boolean = requestMutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        pendingDeferred = deferred
        _pendingRequest.value = PendingConfirmation(UUID.randomUUID().toString(), call)
        try {
            // Deny after a timeout rather than waiting forever: turns can run
            // with NO confirmation UI attached (local API server, cron worker),
            // and an unanswered await would hang that turn permanently. Denial
            // is the safe default for a tool that asked for confirmation. The
            // mutex serializes concurrent requests so one turn cannot overwrite
            // another turn's deferred response.
            kotlinx.coroutines.withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) {
                deferred.await()
            } ?: false
        } finally {
            _pendingRequest.value = null
            pendingDeferred = null
        }
    }

    /**
     * Answer the request identified by [requestId]. A verdict addressed to a
     * request that is no longer pending (already timed out, dismissed, or
     * replaced by a later turn) is ignored, so a stale dialog can never
     * approve a different call than the one it displayed (D9).
     */
    fun submitConfirmation(requestId: String, approved: Boolean) {
        if (_pendingRequest.value?.id == requestId) {
            pendingDeferred?.complete(approved)
        }
    }

    companion object {
        const val CONFIRMATION_TIMEOUT_MS = 60_000L
    }
}
