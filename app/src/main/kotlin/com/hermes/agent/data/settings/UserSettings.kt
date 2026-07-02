package com.hermes.agent.data.settings

data class UserSettings(
    val cloudEnabled: Boolean = false,
    val cloudApiKey: String = "",
    val cloudBaseUrl: String = "https://api.openai.com/v1",
    val cloudModel: String = "gpt-4o-mini",
    val appTheme: String = "MIDNIGHT",
    val reasoningEffort: String = "medium",
    // Specialist (aux) cloud provider. Base URL and API key are optional — when
    // blank, the specialist model runs on the primary provider's endpoint/key.
    // Set them to point the specialist at a fully separate provider.
    val auxModel: String = "gpt-4o-mini",
    val auxBaseUrl: String = "",
    val auxApiKey: String = "",
    // Backup
    val githubPat: String = "",
    val gistId: String = "",
    val lastBackupTimestamp: Long = 0L,
    // True once the Hermes CLI has been detected in Termux (hides the installer).
    val termuxHermesInstalled: Boolean = false,
    // Tool transparency: when true (default), tool-call cards (web search,
    // calendar, etc.) are shown live as the agent works. When false, only the
    // final reply is shown — the agent's tool use stays opaque to the user.
    val showToolCalls: Boolean = true,
)
