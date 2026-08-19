package com.hermes.agent.data.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the agent's `clarify` tool to the chat UI.
 *
 * The existing `confirmationGate` only answers a yes/no question (approve or
 * deny a tool call). A clarification needs the user's actual *answer* — a
 * chosen option or free text — fed back as the tool's result. This bus is the
 * channel: [ClarifyTool][com.hermes.agent.data.tools.ClarifyTool] calls [ask]
 * and suspends; the chat layer observes [pending], renders the question, and
 * calls [answer] (or [cancel]) when the user responds.
 *
 * One clarification is handled at a time — a second [ask] blocks until the
 * first is answered, which matches how the agent runs a single tool loop.
 */
@Singleton
class ClarificationBus @Inject constructor() {

    /** A question awaiting the user's answer. */
    data class Request(
        val question: String,
        /** Predefined choices (may be empty for an open-ended question). */
        val choices: List<String>,
    )

    private val _pending = MutableStateFlow<Request?>(null)

    /** The clarification currently awaiting an answer, or null. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var deferred: CompletableDeferred<String>? = null

    /**
     * Present [question] (with optional [choices]) to the user and suspend
     * until they answer. Returns the user's answer text. Cancellation of the
     * calling coroutine (e.g. the user stops the turn) propagates normally.
     */
    suspend fun ask(question: String, choices: List<String>): String = mutex.withLock {
        val d = CompletableDeferred<String>()
        deferred = d
        _pending.value = Request(question, choices)
        try {
            d.await()
        } finally {
            _pending.value = null
            deferred = null
        }
    }

    /** Supply the user's answer to the pending clarification. No-op if none. */
    fun answer(text: String) {
        deferred?.complete(text)
    }

    /** Abandon the pending clarification (e.g. the turn was cancelled). */
    fun cancel() {
        deferred?.cancel()
    }
}
