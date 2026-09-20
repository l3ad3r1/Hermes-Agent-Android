package com.hermes.agent.data.agent

/**
 * Collects the model's working-out over one tool loop. Every round of the loop is a separate model
 * call and any of them may think, so the text of each is kept in order and the time is the sum of
 * the calls that produced any, which is what "Thought for 6s" should report.
 */
class ReasoningTrace {
    private val parts = mutableListOf<String>()
    var millis: Long = 0L
        private set

    fun record(reasoning: String, callMillis: Long) {
        if (reasoning.isBlank()) return
        parts += reasoning.trim()
        millis += callMillis.coerceAtLeast(0)
    }

    fun text(): String = parts.joinToString("\n\n")
}
