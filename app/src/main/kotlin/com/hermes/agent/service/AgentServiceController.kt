package com.hermes.agent.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin process-wide control surface for the always-on [AgentForegroundService].
 *
 * The UI (Kanban top bar, Settings) observes [running] and calls [start]/[stop];
 * the service updates [running] from its own lifecycle so the toggle stays in sync.
 */
object AgentServiceController {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** Title of the Kanban ticket the background agent is working right now,
     *  or null when idle. Drives the home screen's context-aware status
     *  ("I'm busy working on …") and the FOCUSED eye expression. */
    private val _currentTask = MutableStateFlow<String?>(null)
    val currentTask: StateFlow<String?> = _currentTask

    internal fun setRunning(value: Boolean) {
        _running.value = value
        if (!value) _currentTask.value = null
    }

    internal fun setWorkingOn(taskTitle: String?) { _currentTask.value = taskTitle }

    /** One-shot stream of just-completed ticket titles. The home screen
     *  observes it to trigger Hermes's celebratory bounce. Buffered so an
     *  emission isn't lost if there's briefly no collector. */
    private val _taskCompleted = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val taskCompleted: SharedFlow<String> = _taskCompleted

    internal fun emitTaskCompleted(taskTitle: String) { _taskCompleted.tryEmit(taskTitle) }

    fun start(context: Context) {
        requestBatteryOptimizationExemption(context)
        val intent = Intent(context, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Ask the OS to exempt Hermes from Doze battery optimization so the always-on
     * service survives long idle periods. Salvaged from the App 2 scheduler. No-op
     * if already exempt or unsupported. The user still chooses in the system dialog.
     */
    fun requestBatteryOptimizationExemption(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun stop(context: Context) {
        val intent = Intent(context, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }
}
