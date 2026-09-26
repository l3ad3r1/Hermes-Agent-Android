package com.hermes.agent.ui.chat

/**
 * The token counter under the chat title: how much of the model's context this conversation is
 * using, e.g. `~4.3K / 200K`.
 *
 * The used figure is an estimate (about four characters a token), so it is shown with a `~`. The
 * window is only known for model families we recognise; for anything else the count is shown on
 * its own rather than against a guessed limit, because a wrong limit is worse than none.
 */
object ContextMeter {

    /** Context window in tokens for a model id, or null when the family is not recognised. */
    fun windowFor(model: String): Int? {
        val m = model.lowercase()
        return when {
            "claude" in m -> if ("[1m]" in m) 1_000_000 else 200_000
            "gemini" in m -> 1_000_000
            "gpt-4.1" in m -> 1_000_000
            "gpt-5" in m || "o3" in m || "o4" in m -> 400_000
            "gpt-4o" in m || "gpt-4-turbo" in m -> 128_000
            "deepseek" in m -> 128_000
            "llama-3.1" in m || "llama-3.3" in m || "llama3.1" in m || "llama3.3" in m -> 128_000
            "qwen" in m -> 128_000
            "mistral" in m || "mixtral" in m -> 32_000
            "gemma" in m -> 8_000
            else -> null
        }
    }

    fun format(tokens: Int): String = when {
        tokens < 1_000 -> tokens.toString()
        tokens < 10_000 -> "%.1fK".format(tokens / 1000.0)
        tokens < 1_000_000 -> "${tokens / 1000}K"
        else -> "%.1fM".format(tokens / 1_000_000.0).replace(".0M", "M")
    }

    /** `~4.3K / 200K tokens`, or `~4.3K tokens` when the window is unknown; empty for an empty chat. */
    fun label(estimatedTokens: Int, model: String): String {
        if (estimatedTokens <= 0) return ""
        val used = "~" + format(estimatedTokens)
        val window = windowFor(model) ?: return "$used tokens"
        return "$used / ${format(window)} tokens"
    }
}
