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
import dagger.hilt.android.AndroidEntryPoint
import com.hermes.agent.domain.repository.KanbanRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * Power model (ported from hermes-agent's going-idle / wake-poke session
 * primitives, docs/relay-connector-contract.md): instead of a fixed poll
 * interval, the loop drains all TODO tickets, then suspends on a conflated
 * wake channel. Room's invalidation tracker pokes that channel whenever the
 * TODO count changes, so new tickets wake the agent instantly while an idle
 * board costs zero CPU. A long fallback timeout re-runs the drain even
 * without a poke (self-healing, mirrors hermes-agent's reconcile-on-boot).
 */
@AndroidEntryPoint
class AgentForegroundService : Service() {

    @Inject lateinit var taskProcessor: KanbanTaskProcessor

    @Inject lateinit var kanbanRepository: KanbanRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var wakeWatcherJob: Job? = null

    /** Conflated so any number of board changes while working collapses into
     *  one pending poke — the drain loop re-checks the queue anyway. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    companion object {
        const val CHANNEL_ID = "hermes_agent_service"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.hermes.agent.action.START_AGENT"
        const val ACTION_STOP = "com.hermes.agent.action.STOP_AGENT"

        /** Fallback re-drain interval while idle. The wake channel is the
         *  primary signal; this only guards against a missed poke. */
        private const val IDLE_FALLBACK_MS = 15 * 60_000L
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
            buildNotification("Jeeves active", "Monitoring the board…"),
            serviceType,
        )
        AgentServiceController.setRunning(true)

        // Wake watcher: Room re-emits the TODO count on every board change;
        // a positive count pokes the (conflated) wake channel.
        wakeWatcherJob = scope.launch {
            kanbanRepository.observeTodoCount()
                .distinctUntilChanged()
                .collect { todos -> if (todos > 0) wake.trySend(Unit) }
        }

        loopJob = scope.launch {
            while (isActive) {
                // Drain everything queued, one ticket at a time.
                while (isActive) {
                    val worked = runCatching { tick() }
                        .onFailure { Timber.e(it, "Agent loop tick failed") }
                        .getOrDefault(false)
                    if (!worked) break
                }
                // Going idle: suspend until poked (or the fallback fires).
                AgentServiceController.setWorkingOn(null)
                updateNotification("Jeeves idle", "Waiting for new tickets…")
                withTimeoutOrNull(IDLE_FALLBACK_MS) { wake.receive() }
            }
        }
    }

    /** Works the oldest TODO ticket. Returns false when the queue is empty. */
    private suspend fun tick(): Boolean = taskProcessor.processNext(
        onStarted = { ticket ->
            AgentServiceController.setWorkingOn(ticket.title)
            updateNotification(
                "Working: ${ticket.title.take(28)}",
                "Ticket ${ticket.id} in progress",
            )
        },
        onCompleted = { ticket ->
            // Celebrate: the home screen's eyes do a happy bounce.
            AgentServiceController.emitTaskCompleted(ticket.title)
        },
    )

    private fun stopAgent() {
        loopJob?.cancel()
        loopJob = null
        wakeWatcherJob?.cancel()
        wakeWatcherJob = null
        AgentServiceController.setRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jeeves Service",
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
