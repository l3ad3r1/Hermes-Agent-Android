package com.hermes.agent.domain.plugin

import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the catalog of installed plugins.
 *
 * Phase 3 ships an in-memory implementation backed by the
 * [PluginLifecycleManager]. Phase 4 will persist plugin install state
 * to Room so it survives process restart.
 */
interface PluginRegistry {

    /** Hot stream of all known plugins with their current state. */
    fun observePlugins(): Flow<List<PluginInstance>>

    /** All currently-ACTIVE plugins. Convenience for the orchestrator. */
    suspend fun activePlugins(): List<Plugin>

    /** All tool descriptors advertised by currently-ACTIVE plugins. */
    suspend fun activeToolDescriptors(): List<com.hermes.agent.domain.tool.ToolDescriptor>

    /** Look up a plugin by manifest id. */
    suspend fun byId(id: String): PluginInstance?

    /** Install a plugin. Returns the resulting [PluginInstance]. */
    suspend fun install(plugin: Plugin): PluginInstance

    /** Activate an installed plugin (loads it into the sandbox). */
    suspend fun activate(id: String): PluginLifecycleResult

    /** Suspend an active plugin. */
    suspend fun suspend_(id: String): PluginLifecycleResult

    /** Permanently uninstall a plugin. */
    suspend fun uninstall(id: String)
}

/**
 * One plugin's runtime state, surfaced via [PluginRegistry.observePlugins].
 */
data class PluginInstance(
    val manifest: PluginManifest,
    val state: PluginState,
    val loadedAt: Long? = null,
    val lastError: String? = null,
    val resourceUsage: PluginResourceUsage? = null,
)

/**
 * Resource usage snapshot for a running plugin. Used by the
 * [com.hermes.agent.data.plugin.PluginResourceMonitor] to enforce
 * per-plugin CPU/memory/network limits per Section 3.3 of the plan.
 */
data class PluginResourceUsage(
    val cpuPercent: Float,
    val memoryMb: Int,
    val networkBytesIn: Long,
    val networkBytesOut: Long,
    val lastUpdated: Long,
)
