package com.hermes.agent.domain.model

/**
 * Roles a single chat message can take.
 *
 * Mirrors the OpenAI chat-completions role taxonomy so the same model can be
 * sent verbatim to either the on-device mock or a cloud provider.
 */
enum class MessageRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    companion object {
        fun fromWire(name: String): MessageRole =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) } ?: USER
    }
}
