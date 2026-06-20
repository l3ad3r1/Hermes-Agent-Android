package com.hermes.agent.domain.model

enum class ConnectorType(val displayName: String) {
    WEBHOOK("Webhook"),
    TELEGRAM("Telegram"),
    DISCORD("Discord"),
    SIGNAL("Signal"),
    WHATSAPP("WhatsApp"),
}

data class Connector(
    val id: String,
    val name: String,
    val type: ConnectorType,
    val config: Map<String, String>,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
)
