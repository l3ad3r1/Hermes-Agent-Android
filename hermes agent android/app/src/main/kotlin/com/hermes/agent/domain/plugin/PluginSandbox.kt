package com.hermes.agent.domain.plugin

/**
 * Isolation boundary a [Plugin] runs inside.
 *
 * Per Section 3.3 of the plan: "Plugins communicate with the core
 * application through a gRPC-based inter-process communication channel,
 * ensuring that a misbehaving plugin cannot crash or compromise the
 * main agent process."
 *
 * Phase 3 ships two implementations:
 *   - [com.hermes.agent.data.plugin.InProcessPluginSandbox] — runs
 *     first-party plugins in-process. No isolation, but zero IPC
 *     overhead. Used for everything shipped inside the main APK.
 *   - [com.hermes.agent.data.plugin.GrpcPluginSandbox] — interface
 *     stub for third-party APK plugins loaded via gRPC over a local
 *     UNIX-domain socket. Real implementation deferred until the
 *     gRPC server-side bindings are validated on a Samsung device.
 */
interface PluginSandbox {

    /** Human-readable name for diagnostics. */
    val name: String

    /**
     * Load [plugin] into this sandbox. Calls [Plugin.onLoad] with a
     * sandbox-scoped [PluginContext]. The plugin's tools are
     * registered with the host [com.hermes.agent.domain.tool.ToolRegistry]
     * on success.
     */
    suspend fun load(plugin: Plugin, context: PluginContext): PluginLifecycleResult

    /** Suspend a loaded plugin. */
    suspend fun suspend_(plugin: Plugin): PluginLifecycleResult

    /** Resume a suspended plugin. */
    suspend fun resume(plugin: Plugin): PluginLifecycleResult

    /** Unload a plugin and unregister its tools. */
    suspend fun unload(plugin: Plugin): PluginLifecycleResult

    /**
     * Whether this sandbox is available on the current device. The
     * gRPC sandbox reports `false` on devices that lack the gRPC
     * native libs; the in-process sandbox always reports `true`.
     */
    suspend fun isAvailable(): Boolean
}
