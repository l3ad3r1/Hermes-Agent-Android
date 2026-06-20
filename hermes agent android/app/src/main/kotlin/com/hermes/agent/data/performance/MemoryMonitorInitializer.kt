package com.hermes.agent.data.performance

import android.content.Context
import androidx.startup.Initializer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * App Startup initializer for [MemoryPressureMonitor].
 *
 * Triggered automatically by AndroidX App Startup when the application
 * process starts. Calls [MemoryPressureMonitor.start] so memory pressure
 * is being polled before the user opens the first screen — this matters
 * because the on-device LLM provider subscribes to the monitor's state
 * transitions and needs to shed load promptly when the system comes
 * under pressure.
 *
 * The Initializer is registered in the AndroidManifest under
 * `<provider android:name="androidx.startup.InitializationProvider">`
 * with a `<meta-data>` entry pointing here.
 *
 * Phase 4 ships this as a no-arg Initializer; if Hilt initialization
 * hasn't completed by the time [create] is called, we fall back to
 * late-init via [HermesApp.onCreate].
 */
class MemoryMonitorInitializer : Initializer<MemoryMonitorInitializer> {

    override fun create(context: Context): MemoryMonitorInitializer {
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                MemoryMonitorEntryPoint::class.java,
            )
            entryPoint.monitor().start()
            Timber.tag("Startup").i("MemoryPressureMonitor started via App Startup")
        }.onFailure { t ->
            // Hilt not yet initialized — HermesApp.onCreate will start the monitor.
            Timber.tag("Startup").d("MemoryPressureMonitor deferred to HermesApp: ${t.message}")
        }
        return this
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemoryMonitorEntryPoint {
    fun monitor(): MemoryPressureMonitor
}
