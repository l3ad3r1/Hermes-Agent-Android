package com.hermes.agent.domain.plugin

import com.hermes.agent.domain.tool.ToolDescriptor

/**
 * Phase 3 plugin system — Section 3.3 of the technical plan.
 *
 * Plugins are sandboxed extensions that add capabilities to the agent
 * orchestration layer. Each plugin is packaged as an Android APK module
 * with a defined interface contract that exposes capabilities to the
 * agent. The interface contract specifies four required components:
 *   1. A capability descriptor (what the plugin can do)
 *   2. A tool schema (input/output definitions for function calling)
 *   3. A permission manifest (device resources the plugin requires)
 *   4. A lifecycle handler (initialization, suspension, cleanup callbacks)
 */

/**
 * One capability advertised by a [Plugin].
 *
 * A plugin may advertise multiple capabilities — e.g. a calendar plugin
 * might advertise both "read_calendar" and "write_calendar" so the user
 * can grant them independently.
 */
data class PluginCapability(
    val name: String,
    val description: String,
    val toolDescriptors: List<ToolDescriptor>,
)

/**
 * A device resource a plugin needs access to.
 *
 * Modeled after Android's runtime permission groups so the permission
 * review UI can present them in a familiar format. Plugins that need
 * resources not in this enum must declare a [custom] permission with a
 * human-readable description.
 */
data class PluginPermission(
    val type: PermissionType,
    val rationale: String,
    val custom: String? = null,
)

enum class PermissionType {
    CALENDAR,
    CAMERA,
    CONTACTS,
    LOCATION,
    MICROPHONE,
    PHONE,
    SENSORS,
    SMS,
    STORAGE,
    NETWORK,
    NOTIFICATION,
    CUSTOM,
}

/**
 * Static descriptor advertising a plugin's identity, capabilities, and
 * permission requirements.
 *
 * Mirrors the four required interface contract components from Section
 * 3.3 of the plan:
 *   - capabilities → [capabilities]
 *   - tool schema → each [PluginCapability.toolDescriptors]
 *   - permission manifest → [permissions]
 *   - lifecycle handler → the [Plugin] instance itself
 *
 * @property id Stable unique identifier (reverse-DNS, e.g.
 *   `com.example.weather`).
 * @property displayName Human-readable name for the Plugins UI.
 * @property versionCode Monotonically increasing integer version.
 * @property versionName Human-readable version string.
 * @property author Author / publisher for the Plugins UI.
 * @property signatureFingerprint SHA-256 of the plugin's signing
 *   certificate. Verified against the plugin's declared fingerprint at
 *   install time so a tampered plugin cannot load.
 * @property capabilities Capabilities the plugin advertises.
 * @property permissions Device resources the plugin requires.
 * @property minAppVersion Minimum Hermes app versionCode that supports
 *   this plugin's API surface.
 */
data class PluginManifest(
    val id: String,
    val displayName: String,
    val versionCode: Int,
    val versionName: String,
    val author: String,
    val signatureFingerprint: String,
    val capabilities: List<PluginCapability>,
    val permissions: List<PluginPermission>,
    val minAppVersion: Int = 1,
)

/**
 * Installation / runtime state of a plugin.
 *
 * Surfaced via [PluginRegistry.observePlugins] so the Plugins UI can
 * render the right affordance per state.
 */
enum class PluginState {
    /** Plugin detected but not yet installed. */
    DISCOVERED,

    /** Manifest verified; awaiting user approval of permissions. */
    PENDING_APPROVAL,

    /** Approved and ready; not currently loaded into the sandbox. */
    INSTALLED,

    /** Loaded into the sandbox; capabilities available to the orchestrator. */
    ACTIVE,

    /** Temporarily suspended by the lifecycle manager (e.g. resource pressure). */
    SUSPENDED,

    /** Disabled by the user. */
    DISABLED,

    /** Installation or runtime error. See [PluginInstance.lastError]. */
    ERROR,
}
