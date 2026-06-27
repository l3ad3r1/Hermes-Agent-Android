package com.hermes.agent.util.audit

/**
 * Phase 4 security audit checklist — Section 8 of the plan.
 *
 * A programmatically-checkable list of security controls. Each control
 * has a [status] the app reports on the Settings → Security screen
 * so users (and reviewers) can see at a glance what's enforced and
 * what's still pending.
 *
 * This is the single source of truth — the Settings UI, the worklog,
 * and the security review doc all reference these enum values. Add a
 * new control here when you ship a new security feature; don't sprinkle
 * ad-hoc "is X enabled" booleans elsewhere.
 */
enum class SecurityControl(
    val title: String,
    val description: String,
    val status: ControlStatus,
) {
    ANDROID_KEYSTORE(
        title = "Android Keystore",
        description = "Hardware-backed AES-256-GCM key storage for the cloud API key.",
        status = ControlStatus.ENFORCED,
    ),
    ENCRYPTED_SETTINGS(
        title = "Encrypted settings at rest",
        description = "Cloud API key encrypted via Keystore before being written to DataStore.",
        status = ControlStatus.ENFORCED,
    ),
    CERTIFICATE_PINNING(
        title = "Certificate pinning",
        description = "Cloud LLM endpoints pinned to known SHA-256 hashes (placeholder — replace before release).",
        status = ControlStatus.PARTIAL,
    ),
    BACKUP_EXCLUSION(
        title = "Backup exclusion",
        description = "Conversations, memories, settings, and models excluded from cloud backup / device transfer.",
        status = ControlStatus.ENFORCED,
    ),
    SANDBOXED_PLUGINS(
        title = "Sandboxed plugins",
        description = "First-party plugins run in-process; third-party plugins deferred to Phase 3.x gRPC sandbox.",
        status = ControlStatus.PARTIAL,
    ),
    KNOX_INTEGRATION(
        title = "Samsung Knox integration",
        description = "Knox SDK probe + KPE license activation (stub — full integration in Phase 3.x on Samsung devices).",
        status = ControlStatus.PENDING,
    ),
    TOOL_CONFIRMATION_GATE(
        title = "Tool confirmation gate",
        description = "Side-effecting tools (calendar, device settings) require explicit user approval before execution.",
        status = ControlStatus.ENFORCED,
    ),
    NETWORK_ENCRYPTION(
        title = "TLS for all network traffic",
        description = "All cloud LLM calls use HTTPS; plaintext HTTP refused by OkHttp.",
        status = ControlStatus.ENFORCED,
    ),
    RAG_CONTENT_ISOLATION(
        title = "RAG content isolation",
        description = "Ingested documents stay on-device; never sent to cloud unless explicitly requested by the user.",
        status = ControlStatus.ENFORCED,
    ),
    MEMORY_PRESSURE_SHEDDING(
        title = "Memory pressure shedding",
        description = "On-device LLM weights unloaded when system memory drops below 2 GB.",
        status = ControlStatus.ENFORCED,
    ),
    SECURE_RANDOM_IDS(
        title = "Cryptographically random IDs",
        description = "All persisted entity IDs are UUIDv4 from java.util.UUID (uses SecureRandom internally).",
        status = ControlStatus.ENFORCED,
    ),
    PROGUARD_OBFUSCATION(
        title = "Release obfuscation",
        description = "R8 / ProGuard obfuscation + resource shrinking enabled on release builds.",
        status = ControlStatus.ENFORCED,
    ),
}

enum class ControlStatus {
    /** Fully enforced in this build. */
    ENFORCED,

    /** Partially enforced — see [SecurityControl.description] for what's pending. */
    PARTIAL,

    /** Declared but not yet enforced; staged for a future release. */
    PENDING,
}

object SecurityAudit {
    /** All controls, in the order they should appear in the Settings UI. */
    val all: List<SecurityControl> = SecurityControl.entries.toList()

    val enforcedCount: Int get() = all.count { it.status == ControlStatus.ENFORCED }
    val partialCount: Int get() = all.count { it.status == ControlStatus.PARTIAL }
    val pendingCount: Int get() = all.count { it.status == ControlStatus.PENDING }
}
