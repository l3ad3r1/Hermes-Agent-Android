package com.hermes.agent.domain.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide "is the agent thinking right now?" signal.
 *
 * [OrchestratorImpl] marks every run (chat reply, delegate sub-run,
 * background ticket, API-server request) with [begin]/[end], so any UI can
 * reflect live computation — the home screen's eyes switch to the THINKING
 * mood while a reply is being composed anywhere in the app.
 *
 * A counter (not a boolean) because runs can overlap: the kanban service
 * can be working a ticket while the user chats.
 */
object AgentActivity {

    private val active = AtomicInteger(0)
    private val _activeRuns = MutableStateFlow(0)

    /** Number of orchestrator runs in flight. */
    val activeRuns: StateFlow<Int> = _activeRuns

    /** True while at least one orchestrator run is in flight. */
    val thinking = _activeRuns.map { it > 0 }

    fun begin() {
        _activeRuns.value = active.incrementAndGet()
    }

    fun end() {
        _activeRuns.value = active.decrementAndGet().coerceAtLeast(0)
    }
}
