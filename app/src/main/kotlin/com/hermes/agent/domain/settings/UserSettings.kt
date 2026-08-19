package com.hermes.agent.domain.settings
import com.hermes.agent.domain.llm.*

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
    // OpenAI-compatible provider credentials. Provider metadata comes from the
    // built-in Desktop-compatible registry; API keys are encrypted individually.
    val cloudProviderProfiles: List<CloudProviderProfile> = emptyList(),
    // Local AI Model
    // A custom .gguf picked via SAF (content:// URI). When set, it overrides the
    // downloaded catalog model.
    val localModelUri: String = "",
    // Which catalog model (ModelCatalog.MODELS) the user has chosen to
    // download/use. Blank = ModelCatalog.DEFAULT.
    val selectedModelId: String = "",
    // Absolute directory the model is downloaded into. Blank = the default
    // top-level "AI Models" folder on shared storage. A user-typed path here
    // (needs All-Files access) sends downloads elsewhere.
    val modelDownloadDir: String = "",
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
    // Explicit opt-in for hands-free phone actions. This never covers shell,
    // Termux, or raw settings commands, and background runs remain denied by
    // ToolExecutionPolicy.
    val autoApprovePhoneActions: Boolean = false,
    // Allows a restricted set of phone tools from background agent runs. The
    // user must authenticate with the device credential before enabling it.
    val trustedBackgroundPhoneActions: Boolean = false,
    // Local OpenAI-compatible API server (v0.7.26). When enabled, Hermes runs
    // an embedded HTTP server exposing /v1/chat/completions so other apps on
    // the device (or LAN) can use the agent as a backend.
    val apiServerEnabled: Boolean = false,
    val apiServerPort: Int = 8642,
    // Bearer token required on API requests. Blank = no auth (only safe on the
    // loopback bind). Generated on first enable.
    val apiServerKey: String = "",
    // When false (default), the server binds to 127.0.0.1 only (same-device
    // clients). When true, it binds to 0.0.0.0 so other devices on the LAN can
    // reach it — a key is then strongly recommended.
    val apiServerAllowLan: Boolean = false,
    // Remote shell over SSH (v0.7.29): when configured, the shell tool can run
    // commands on this host via target='remote' (roadmap: remote terminal
    // backends — through SSH you also reach Docker on the host).
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "",
    val sshPassword: String = "",
    /**
     * Passphrase that protects credentials inside a backup archive.
     *
     * Generated once and remembered so routine backups need no interaction.
     * It is what makes a backup portable: the per-install keystore key cannot
     * leave the device, so anything encrypted with it is dead on arrival
     * elsewhere. This travels with the user instead.
     */
    val backupPassphrase: String = "",
    // Telegram Bot Gateway: 24/7 self-hosted Telegram bot running in background service
    val telegramBotEnabled: Boolean = false,
    val telegramBotToken: String = "",
    val telegramAllowedUserIds: String = "",
)
