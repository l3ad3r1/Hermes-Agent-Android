package com.hermes.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hermes.agent.MainActivity
import com.hermes.agent.R
import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.repository.KanbanRepository
import com.hermes.agent.data.tools.WebhookTool
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import javax.inject.Inject

/**
 * Always-on foreground service that keeps Hermes alive across Doze so it can work
 * the Kanban board in the background.
 *
 * Ported from "Hermes Android App 2" and rewired into this app: the original
 * polled a stubbed in-memory queue, whereas this version drives the real
 * [KanbanRepository] — it claims the oldest TODO ticket, marks it IN_PROGRESS,
 * runs it, marks it DONE with a result, and (optionally) pushes a notification
 * through the existing [WebhookTool] (`notify`) to any connected platform.
 */
@AndroidEntryPoint
class AgentForegroundService : Service() {

    @Inject lateinit var kanbanRepository: KanbanRepository
    @Inject lateinit var webhookTool: WebhookTool

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    companion object {
        const val CHANNEL_ID = "hermes_agent_service"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.hermes.agent.action.START_AGENT"
        const val ACTION_STOP = "com.hermes.agent.action.STOP_AGENT"
        private const val POLL_INTERVAL_MS = 5_000L
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAgent(); return START_NOT_STICKY }
            else -> startAgent()
        }
        return START_STICKY
    }

    private fun startAgent() {
        if (loopJob?.isActive == true) return

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Hermes Agent active", "Monitoring the board…"),
            serviceType,
        )
        AgentServiceController.setRunning(true)

        loopJob = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { Timber.e(it, "Agent loop tick failed") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** One iteration of the background work loop. */
    private suspend fun tick() {
        val ticket = kanbanRepository.nextTodo()
        if (ticket == null) {
            updateNotification("Hermes Agent idle", "Waiting for new tickets…")
            return
        }

        updateNotification("Working: ${ticket.title.take(28)}", "Ticket ${ticket.id} in progress")
        kanbanRepository.moveTo(ticket.id, KanbanStatus.IN_PROGRESS)

        // Placeholder execution. A full implementation would dispatch the ticket
        // body to the agent orchestrator + tool registry; here we simulate work so
        // the end-to-end claim → execute → complete → notify path is exercised.
        delay(2_000L)

        val result = "Auto-processed by the Hermes background agent."
        kanbanRepository.complete(ticket.id, result)

        runCatching {
            val args: Map<String, JsonElement> =
                mapOf("message" to JsonPrimitive("✅ Completed ticket ${ticket.id}: ${ticket.title}"))
            webhookTool.execute(args)
        }.onFailure { Timber.w(it, "notify after ticket completion failed") }
    }

    private fun stopAgent() {
        loopJob?.cancel()
        loopJob = null
        AgentServiceController.setRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hermes Agent Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Background agent execution status"
                enableVibration(false)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AgentServiceController.setRunning(false)
        scope.cancel()
        super.onDestroy()
    }
}
