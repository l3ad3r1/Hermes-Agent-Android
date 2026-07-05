package com.hermes.agent.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide control surface + status for the [ApiServerService].
 *
 * Settings observes [status] to render the toggle and the reachable URL,
 * and calls [start]/[stop]; the service updates [status] from its own
 * lifecycle so the UI stays in sync.
 */
object ApiServerController {

    data class Status(
        val running: Boolean = false,
        val host: String = "127.0.0.1",
        val port: Int = 8642,
        val error: String? = null,
    ) {
        val baseUrl: String get() = "http://$host:$port/v1"
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    internal fun setRunning(host: String, port: Int) {
        _status.value = Status(running = true, host = host, port = port, error = null)
    }

    internal fun setStopped() {
        _status.value = _status.value.copy(running = false, error = null)
    }

    internal fun setError(message: String) {
        _status.value = _status.value.copy(running = false, error = message)
    }

    fun start(context: Context) {
        val intent = Intent(context, ApiServerService::class.java).apply {
            action = ApiServerService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, ApiServerService::class.java).apply {
            action = ApiServerService.ACTION_STOP
        }
        context.startService(intent)
    }
}
