package com.hermes.agent.data.settings

data class UserSettings(
    val cloudEnabled: Boolean = false,
    val cloudApiKey: String = "",
    val cloudBaseUrl: String = "https://api.openai.com/v1",
    val cloudModel: String = "gpt-4o-mini",
    val appTheme: String = "MIDNIGHT",
    val reasoningEffort: String = "medium",
    val auxModel: String = "gpt-4o-mini",
    // On-device LLM (llama.cpp)
    val onDeviceEnabled: Boolean = false,
    val onDeviceModelPath: String = "",
    // Backup
    val githubPat: String = "",
    val gistId: String = "",
    val lastBackupTimestamp: Long = 0L,
)
