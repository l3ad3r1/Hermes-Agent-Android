package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginSandbox
import com.hermes.agent.domain.tool.ToolRegistry
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process [PluginSandbox] implementation.
 *
 * Used for first-party plugins that ship inside the main Hermes APK.
 * Provides zero isolation (a misbehaving in-process plugin can crash
 * the host) but zero IPC overhead.
 *
 * Third-party plugins must instead go through the
 * [GrpcPluginSandbox] (stub) which spawns each plugin in its own
 * process and communicates via gRPC over a local UNIX-domain socket.
 *
 * On [load]: calls [Plugin.onLoad], then registers the plugin's tools
 *   with the global [ToolRegistry] so the orchestrator can invoke them.
 * On [unload]: unregisters the tools, then calls [Plugin.onUnload].
 */
@Singleton
class InProcessPluginSandbox @Inject constructor(
    private val toolRegistry: ToolRegistry,
) : PluginSandbox {

    override val name: String = "InProcess"

    override suspend fun load(plugin: Plugin, context: PluginContext): PluginLifecycleResult {
        return runCatching {
            val result = plugin.onLoad(context)
            when (result) {
                is PluginLifecycleResult.Success -> {
                    plugin.tools().forEach { toolRegistry.register(it) }
                    Timber.tag("PluginSandbox").i(
                        "loaded %s, registered %d tools",
                        plugin.manifest.id, plugin.manifest.capabilities.size,
                    )
                    PluginLifecycleResult.Success
                }
                is PluginLifecycleResult.Failure -> result
            }
        }.getOrElse { t ->
            Timber.tag("PluginSandbox").w(t, "load failed for %s", plugin.manifest.id)
            PluginLifecycleResult.Failure(t.message ?: "load failed", recoverable = false)
        }
    }

    override suspend fun suspend_(plugin: Plugin): PluginLifecycleResult =
        runCatching { plugin.onSuspend() }
            .getOrElse { PluginLifecycleResult.Failure(it.message ?: "suspend failed") }

    override suspend fun resume(plugin: Plugin): PluginLifecycleResult =
        runCatching { plugin.onResume() }
            .getOrElse { PluginLifecycleResult.Failure(it.message ?: "resume failed") }

    override suspend fun unload(plugin: Plugin): PluginLifecycleResult {
        return runCatching {
            // Unregister tools first so no new invocations arrive mid-teardown.
            plugin.tools().forEach { toolRegistry.unregister(it.descriptor.name) }
            plugin.onUnload()
        }.getOrElse { t ->
            Timber.tag("PluginSandbox").w(t, "unload failed for %s", plugin.manifest.id)
            PluginLifecycleResult.Failure(t.message ?: "unload failed")
        }
    }

    override suspend fun isAvailable(): Boolean = true
}
