package com.hermes.agent.domain.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the agent is doing right now, for UI that wants to show more than a
 * binary busy state.
 *
 * These are the real stages of an orchestrator turn rather than decoration —
 * see where each one is set in `OrchestratorImpl.run`.
 */
enum class AgentPhase {
    /** Nothing in flight. */
    IDLE,

    /** Routing the message and building the execution plan. */
    SOLVING,

    /** Retrieval: memories, RAG documents, user model, skill match. */
    SEARCHING,

    /** Waiting on the model — prompt sent, no reply text back yet. */
    THINKING,

    /** A tool call is executing. */
    WORKING,

    /** Reply text is arriving. */
    COMPOSING,
}

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
    private val _phase = MutableStateFlow(AgentPhase.IDLE)

    /** Number of orchestrator runs in flight. */
    val activeRuns: StateFlow<Int> = _activeRuns

    /** True while at least one orchestrator run is in flight. */
    val thinking = _activeRuns.map { it > 0 }

    /**
     * The stage the current work is at.
     *
     * Last-writer-wins, deliberately: when runs overlap there is no single
     * truthful phase, and a per-run stack would buy precision nobody can
     * perceive in a spinner. [begin] and [end] keep the edges honest — the
     * phase always falls back to [AgentPhase.IDLE] once the last run ends.
     */
    val phase: StateFlow<AgentPhase> = _phase

    fun begin() {
        _activeRuns.value = active.incrementAndGet()
    }

    fun end() {
        // Clamped inside the atomic, not just on the way out. Coercing only the
        // exposed value lets an unmatched end() drive the counter negative, and
        // every later begin() then climbs back from below zero — leaving the
        // app looking idle while work is actually running.
        val remaining = active.updateAndGet { (it - 1).coerceAtLeast(0) }
        _activeRuns.value = remaining
        if (remaining == 0) _phase.value = AgentPhase.IDLE
    }

    /** Report the stage of a run. Ignored when nothing is in flight. */
    fun setPhase(value: AgentPhase) {
        if (active.get() > 0) _phase.value = value
    }
}
