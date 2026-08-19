package com.hermes.agent.domain.tool

import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.security.DeviceAuthenticationService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One tool call waiting for a human verdict, addressed by a unique id. */
data class PendingConfirmation(
    val id: String,
    val call: ToolCall,
)

@Singleton
class ToolConfirmationService @Inject constructor(
    private val authorizationSettings: ToolAuthorizationSettings,
    private val deviceAuthenticationService: DeviceAuthenticationService,
) {
    private val requestMutex = Mutex()
    private var pendingDeferred: CompletableDeferred<Boolean>? = null

    private val _pendingRequest = MutableStateFlow<PendingConfirmation?>(null)
    val pendingRequest: StateFlow<PendingConfirmation?> = _pendingRequest

    suspend fun awaitConfirmation(call: ToolCall): Boolean = requestMutex.withLock {
        if (call.name in BIOMETRIC_REQUIRED_TOOLS) {
            Timber.tag("ToolConfirmation").i("Requesting device authentication for tool=%s", call.name)
            return@withLock deviceAuthenticationService.authenticate(
                title = "Approve ${call.name.replace('_', ' ')}",
                reason = "Confirm with your fingerprint or phone passcode",
            )
        }
        if (
            call.name in AUTO_APPROVABLE_PHONE_TOOLS &&
            authorizationSettings.autoApprovePhoneActions()
        ) {
            Timber.tag("ToolConfirmation").i("Auto-approved phone tool=%s", call.name)
            return@withLock true
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingDeferred = deferred
        val request = PendingConfirmation(UUID.randomUUID().toString(), call)
        _pendingRequest.value = request
        Timber.tag("ToolConfirmation").i("Awaiting request=%s tool=%s", request.id, call.name)
        try {
            // Deny after a timeout rather than waiting forever: turns can run
            // with NO confirmation UI attached (local API server, cron worker),
            // and an unanswered await would hang that turn permanently. Denial
            // is the safe default for a tool that asked for confirmation. The
            // mutex serializes concurrent requests so one turn cannot overwrite
            // another turn's deferred response.
            val approved = kotlinx.coroutines.withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) {
                deferred.await()
            } ?: false
            Timber.tag("ToolConfirmation").i(
                "Resolved request=%s tool=%s approved=%s",
                request.id,
                call.name,
                approved,
            )
            approved
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
        Timber.tag("ToolConfirmation").i(
            "Submitted request=%s current=%s approved=%s",
            requestId,
            _pendingRequest.value?.id,
            approved,
        )
        if (_pendingRequest.value?.id == requestId) {
            pendingDeferred?.complete(approved)
        }
    }

    companion object {
        const val CONFIRMATION_TIMEOUT_MS = 60_000L

        val AUTO_APPROVABLE_PHONE_TOOLS = setOf(
            "alarm",
            "navigation",
            "communication",
            "media_control",
            "device_control",
            "calendar_add_event",
            "app_launch",
        )

        val BIOMETRIC_REQUIRED_TOOLS = setOf("shell", "termux")
    }
}
