package com.hermes.agent.data.memory

import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole

/**
 * Sliding-window short-term memory.
 *
 * Implements the "short-term episodic memory" half of Section 6.2 of
 * the plan: "Short-term episodic memory captures the current
 * conversation context, including recent user statements, agent
 * responses, tool invocations, and their results. This memory is
 * maintained as a sliding window of token-limited conversation turns
 * (typically the last 20–30 exchanges)."
 *
 * The orchestrator pulls the recent window from this store when
 * building each LLM prompt. The store is per-conversation; multiple
 * conversations do not share state.
 *
 * Phase 2 implements the window as a simple ring buffer with a
 * per-conversation token budget. Phase 3 will add token-aware
 * summarization when the window would overflow (rather than dropping
 * the oldest turn outright).
 */
class ShortTermMemory(
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {

    private val turns = ArrayDeque<Turn>()

    /** Append a turn. Oldest turns are dropped to stay within [maxTurns]. */
    fun append(turn: Turn) {
        turns.addLast(turn)
        while (turns.size > maxTurns) {
            turns.removeFirst()
        }
        enforceTokenBudget()
    }

    /** Snapshot of the current window, oldest-first. */
    fun snapshot(): List<Turn> = turns.toList()

    /** Reset the window (e.g. when the user clears the conversation). */
    fun clear() = turns.clear()

    /**
     * Build an LLM-ready message list from the window. Optionally strip
     * the system prompt — the orchestrator adds its own per-agent system
     * prompt.
     */
    fun toLlmMessages(includeSystem: Boolean = false): List<com.hermes.agent.data.llm.LlmMessage> {
        return turns
            .filter { includeSystem || it.role != MessageRole.SYSTEM }
            .map { turn ->
                com.hermes.agent.data.llm.LlmMessage(
                    role = turn.role.wireName,
                    content = turn.content,
                )
            }
    }

    private fun enforceTokenBudget() {
        var tokens = totalTokens()
        while (tokens > maxTokens && turns.size > 1) {
            val dropped = turns.removeFirst()
            tokens -= dropped.estimatedTokens
        }
    }

    private fun totalTokens(): Int = turns.sumOf { it.estimatedTokens }

    /** A single conversational turn in the short-term window. */
    data class Turn(
        val role: MessageRole,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        /** Rough token estimate: 1 token ≈ 4 characters. */
        val estimatedTokens: Int get() = (content.length / 4).coerceAtLeast(1)
    }

    companion object {
        const val DEFAULT_MAX_TURNS = 30
        const val DEFAULT_MAX_TOKENS = 4_096
    }
}
