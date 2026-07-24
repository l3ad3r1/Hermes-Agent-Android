package com.hermes.agent.domain.agent

import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.domain.tool.ToolResult

data class ToolExecutionObservation(
    val call: ToolCall,
    val result: ToolResult,
)

enum class ExecutionStopReason {
    REPEATED_NO_PROGRESS,
}

/** Creates isolated progress-policy state for one LLM/tool loop. */
interface ExecutionGuard {
    fun openSession(): ExecutionGuardSession
}

interface ExecutionGuardSession {
    fun observeRound(observations: List<ToolExecutionObservation>): ExecutionStopReason?
}
