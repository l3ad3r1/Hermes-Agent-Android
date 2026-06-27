package com.hermes.agent.data.performance

import android.app.ActivityManager
import android.content.Context
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.util.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiered memory pressure monitor — Section 5.2 of the plan.
 *
 * "When the system reports available memory below a 2 GB threshold, the
 * agent gracefully offloads the on-device LLM weights to flash storage,
 * retains only the vector database index in memory for RAG queries,
 * and switches all inference to cloud mode until memory pressure
 * subsides."
 *
 * Phase 4 implementation:
 *   - Polls [ActivityManager.MemoryInfo] every [POLL_INTERVAL_MS].
 *   - Classifies pressure into three tiers: NORMAL, ELEVATED, CRITICAL.
 *   - Exposes a hot [StateFlow] of [MemoryPressureState] that the
 *     on-device LLM provider, vector store, and orchestrator subscribe
 *     to for shedding decisions.
 *   - Surfaces the current state to the Settings UI diagnostic panel
 *     per Section 5.2 ("fully observable through a diagnostic panel in
 *     the app settings").
 *
 * The actual shedding (unloading LLM weights, switching to cloud mode)
 * is performed by the subscribers — this monitor only reports state.
 */
@Singleton
class MemoryPressureMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
) {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var pollJob: Job? = null

    private val _state = MutableStateFlow(MemoryPressureState.NORMAL)
    val state: StateFlow<MemoryPressureState> = _state.asStateFlow()

    /** Start polling. Safe to call multiple times. */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                tick()
                delay(POLL_INTERVAL_MS)
            }
        }
        Timber.tag("MemoryMonitor").i("started polling every %dms", POLL_INTERVAL_MS)
    }

    /** Stop polling. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Read the current memory state once and update [_state]. */
    suspend fun tick() {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availableMb = (memInfo.availMem / (1024 * 1024)).toInt()
        val totalMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val thresholdMb = (memInfo.threshold / (1024 * 1024)).toInt()
        val isLowMemory = memInfo.lowMemory

        val newState = when {
            availableMb < CRITICAL_THRESHOLD_MB -> MemoryPressureState.CRITICAL
            availableMb < ELEVATED_THRESHOLD_MB -> MemoryPressureState.ELEVATED
            else -> MemoryPressureState.NORMAL
        }

        if (newState != _state.value) {
            Timber.tag("MemoryMonitor").w(
                "pressure transition: %s → %s (avail=%dMB total=%dMB lowMem=%s)",
                _state.value, newState, availableMb, totalMb, isLowMemory,
            )
            _state.value = newState
        }
    }

    /**
     * One-shot snapshot of the current memory situation for the Settings
     * diagnostic panel.
     */
    suspend fun snapshot(): MemorySnapshot {
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return MemorySnapshot(0, 0, 0, false, _state.value)
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return MemorySnapshot(
            availableMb = (memInfo.availMem / (1024 * 1024)).toInt(),
            totalMb = (memInfo.totalMem / (1024 * 1024)).toInt(),
            thresholdMb = (memInfo.threshold / (1024 * 1024)).toInt(),
            isLowMemory = memInfo.lowMemory,
            state = _state.value,
        )
    }

    companion object {
        /** Available memory below this triggers ELEVATED state. */
        const val ELEVATED_THRESHOLD_MB = 3_000  // 3 GB

        /** Available memory below this triggers CRITICAL state. */
        const val CRITICAL_THRESHOLD_MB = 2_000  // 2 GB — matches the plan

        const val POLL_INTERVAL_MS = 15_000L  // 15s
    }
}

enum class MemoryPressureState {
    /** Plenty of headroom; on-device LLM and vector store can stay resident. */
    NORMAL,

    /** Getting tight; subscribers should consider shedding non-essential state. */
    ELEVATED,

    /**
     * Critical — subscribers should unload the on-device LLM weights and
     * switch inference to cloud mode per Section 5.2 of the plan.
     */
    CRITICAL,
}

data class MemorySnapshot(
    val availableMb: Int,
    val totalMb: Int,
    val thresholdMb: Int,
    val isLowMemory: Boolean,
    val state: MemoryPressureState,
)
