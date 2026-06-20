package com.hermes.agent.domain.plugin

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.flow.Flow

/**
 * Contract every Hermes plugin must satisfy.
 *
 * Per Section 3.3 of the plan: "Plugins communicate with the core
 * application through a gRPC-based inter-process communication channel,
 * ensuring that a misbehaving plugin cannot crash or compromise the
 * main agent process."
 *
 * Phase 3 ships two sandbox implementations:
 *   - [com.hermes.agent.data.plugin.InProcessPluginSandbox] — loads
 *     plugins in-process (no IPC). Used for first-party plugins that
 *     ship inside the main APK. Fast, no isolation.
 *   - [com.hermes.agent.data.plugin.GrpcPluginSandbox] — stub for
 *     loading third-party plugins from standalone APKs via gRPC over
 *     local UNIX-domain sockets. Real implementation deferred to a
 *     Phase 3.x point release once the gRPC Android server-side
 *     bindings are validated on a Samsung device.
 *
 * Lifecycle hooks:
 *   - [onLoad]    — called once when the plugin enters ACTIVE state.
 *                   Performs any expensive initialization (loading
 *                   config, opening DBs).
 *   - [onSuspend] — called when the lifecycle manager decides to
 *                   reclaim resources. The plugin should release what
 *                   it can and quiesce.
 *   - [onResume]  — called after [onSuspend] when the plugin is brought
 *                   back to ACTIVE.
 *   - [onUnload]  — called once when the plugin leaves ACTIVE state
 *                   permanently. Performs full cleanup.
 *
 * Tool execution:
 *   - [tools] returns the live [Tool] instances this plugin exposes.
 *     The [PluginSandbox] registers them in the global
 *     [com.hermes.agent.domain.tool.ToolRegistry] on [onLoad] and
 *     removes them on [onUnload].
 */
interface Plugin {

    val manifest: PluginManifest

    /** Live Tool instances. Called after [onLoad]. */
    fun tools(): List<Tool>

    /** Called once when the plugin enters ACTIVE state. */
    suspend fun onLoad(context: PluginContext): PluginLifecycleResult

    /** Called when the lifecycle manager reclaims resources. */
    suspend fun onSuspend(): PluginLifecycleResult

    /** Called after [onSuspend] when the plugin is brought back to ACTIVE. */
    suspend fun onResume(): PluginLifecycleResult

    /** Called once when the plugin leaves ACTIVE state permanently. */
    suspend fun onUnload(): PluginLifecycleResult
}

/**
 * Result of a plugin lifecycle hook.
 */
sealed class PluginLifecycleResult {
    object Success : PluginLifecycleResult()
    data class Failure(val message: String, val recoverable: Boolean = false) : PluginLifecycleResult()
}

/**
 * Read-only handle to host services exposed to plugins.
 *
 * Plugins receive a [PluginContext] in [Plugin.onLoad]. It provides
 * controlled access to host capabilities (logging, settings, the host
 * LLM router) without giving plugins direct access to the Android
 * Context — which would let them escape the sandbox.
 */
interface PluginContext {
    fun log(tag: String, level: LogLevel, message: String, throwable: Throwable? = null)
    suspend fun hostSetting(key: String): String?
    fun hostAppVersion(): Int
}

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }
