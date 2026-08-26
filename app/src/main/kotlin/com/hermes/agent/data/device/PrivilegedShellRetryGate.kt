package com.hermes.agent.data.device

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gatekeeper for privileged shell execution.
 *
 * If a previous privileged process timed out or crashed without clean verification,
 * this gate enters DIRTY_UNWIND state and refuses further execution until explicitly reset
 * or acknowledged, preventing orphan or corrupted background processes from being compounded.
 */
@Singleton
class PrivilegedShellRetryGate @Inject constructor() {

    enum class State {
        IDLE,
        RUNNING,
        DIRTY_UNWIND,
    }

    data class GateStatus(
        val state: State,
        val reason: String = "",
    )

    private val _status = MutableStateFlow(GateStatus(State.IDLE))
    val status: StateFlow<GateStatus> = _status.asStateFlow()

    @Synchronized
    fun canExecute(): Boolean = _status.value.state != State.DIRTY_UNWIND

    @Synchronized
    fun onExecutionStart() {
        if (_status.value.state != State.DIRTY_UNWIND) {
            _status.value = GateStatus(State.RUNNING)
        }
    }

    @Synchronized
    fun onExecutionSuccess() {
        if (_status.value.state == State.RUNNING) {
            _status.value = GateStatus(State.IDLE)
        }
    }

    @Synchronized
    fun onExecutionFailure(unverifiedUnwind: Boolean, reason: String) {
        if (unverifiedUnwind) {
            _status.value = GateStatus(State.DIRTY_UNWIND, reason)
        } else if (_status.value.state == State.RUNNING) {
            _status.value = GateStatus(State.IDLE)
        }
    }

    @Synchronized
    fun resetGate() {
        _status.value = GateStatus(State.IDLE)
    }
}
