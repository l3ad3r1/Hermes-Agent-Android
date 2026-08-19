package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginSandbox
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * gRPC-based [PluginSandbox] — Phase 3 interface stub.
 *
 * Per Section 3.3 of the plan: "Plugins are packaged as Android APK
 * modules with a defined interface contract that exposes capabilities
 * to the agent orchestration layer." Third-party plugins run in their
 * own process, isolated from the host by the Android sandbox, and
 * communicate with the host via gRPC over a local UNIX-domain socket.
 *
 * What this stub does:
 *   - Implements the [PluginSandbox] contract so the rest of the app
 *     (PluginRegistry, PluginLifecycleManager, UI) can be built and
 *     exercised end-to-end against third-party plugins once the gRPC
 *     bindings ship.
 *   - Probes for the gRPC native library at runtime. If absent (which
 *     is the case in Phase 3 since we haven't added the grpc-android
 *     dependency yet), [isAvailable] returns false and the registry
 *     routes third-party plugin installs to a "needs Phase 3.x update"
 *     error state.
 *
 * What this stub does NOT do:
 *   - Spawn a child process for the plugin APK.
 *   - Bind a gRPC server socket in the host.
 *   - Marshal Tool invocations over the wire.
 *
 * Phase 3.x will swap the body of [load] / [suspend_] / [resume] /
 * [unload] for real gRPC calls. The public contract stays identical.
 */
@Singleton
class GrpcPluginSandbox @Inject constructor() : PluginSandbox {

    override val name: String = "Grpc"

    override suspend fun load(plugin: Plugin, context: PluginContext): PluginLifecycleResult {
        if (!isAvailable()) {
            return PluginLifecycleResult.Failure(
                message = "gRPC sandbox not yet available — third-party plugins require " +
                    "the Phase 3.x update. Plugin ${plugin.manifest.id} cannot be loaded.",
                recoverable = false,
            )
        }
        // Phase 3.x: spawn plugin process, bind gRPC server, marshal onLoad.
        return PluginLifecycleResult.Failure("not implemented", recoverable = false)
    }

    override suspend fun suspend_(plugin: Plugin): PluginLifecycleResult =
        PluginLifecycleResult.Failure("not implemented", recoverable = false)

    override suspend fun resume(plugin: Plugin): PluginLifecycleResult =
        PluginLifecycleResult.Failure("not implemented", recoverable = false)

    override suspend fun unload(plugin: Plugin): PluginLifecycleResult =
        PluginLifecycleResult.Failure("not implemented", recoverable = false)

    override suspend fun isAvailable(): Boolean {
        // Probe for the gRPC native library. Phase 3 always returns false
        // because the dependency isn't on the classpath yet — see
        // docs/PHASE3.md § "Plugin system: Phase 3 vs Phase 3.x".
        return runCatching {
            System.loadLibrary("grpc_wrap")
            Timber.tag("GrpcSandbox").i("gRPC native lib detected")
            true
        }.getOrElse {
            Timber.tag("GrpcSandbox").d("gRPC native lib not present: ${it.message}")
            false
        }
    }
}
