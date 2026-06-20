package com.hermes.agent.data.settings

/**
 * Snapshot of user-configurable settings.
 *
 * Stored in DataStore Preferences (not Room) so it survives app upgrades
 * without a migration. See [SettingsRepositoryImpl] for the storage layer.
 */
data class UserSettings(
    /** Whether on-device inference is enabled. */
    val onDeviceEnabled: Boolean = true,
    /** Whether cloud fallback is enabled. */
    val cloudEnabled: Boolean = false,
    /** OpenAI-compatible API key. Stored in DataStore; consider moving to
     *  Android Keystore-encrypted blob in Phase 4. */
    val cloudApiKey: String = "",
    /** OpenAI-compatible base URL. Defaults to OpenAI production. */
    val cloudBaseUrl: String = "https://api.openai.com/v1",
    /** Cloud model id. */
    val cloudModel: String = "gpt-4o-mini",
    /** On-device model id (informational until MLC-LLM is wired in Phase 2). */
    val onDeviceModel: String = "hermes-3-8b-q4f16",
    /** 0..1 — requests classified above this complexity are routed to cloud. */
    val complexityThreshold: Float = 0.6f,
    /** On-device LLM is unloaded after this many minutes idle. */
    val idleUnloadMinutes: Int = 5,
    /** Selected UI theme. One of: MIDNIGHT, PAPER, HERMES_BLUE. */
    val appTheme: String = "MIDNIGHT",
)
