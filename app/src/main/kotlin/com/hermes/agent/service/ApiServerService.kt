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
import com.hermes.agent.data.server.HermesApiServer
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.NetworkInterface
import javax.inject.Inject

/**
 * Foreground service hosting the [HermesApiServer]. A network server must
 * survive Doze and app-backgrounding, so it runs as a `dataSync` foreground
 * service with an ongoing notification — the same pattern as
 * [AgentForegroundService].
 *
 * Started/stopped from Settings via [ApiServerController].
 */
@AndroidEntryPoint
class ApiServerService : Service() {

    @Inject lateinit var orchestrator: Orchestrator
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var conversationRepository: ConversationRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var server: HermesApiServer? = null

    /** The start in flight. `server` is only set once settings are read, so it alone
     *  cannot stop a second start racing the first onto the same port. */
    private var startJob: Job? = null

    companion object {
        const val CHANNEL_ID = "hermes_api_server"
        const val NOTIFICATION_ID = 2002
        const val ACTION_START = "com.hermes.agent.action.START_API_SERVER"
        const val ACTION_STOP = "com.hermes.agent.action.STOP_API_SERVER"
        const val ACTION_RESTART = "com.hermes.agent.action.RESTART_API_SERVER"
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopServer(); return START_NOT_STICKY }
            ACTION_RESTART -> { stopServer(stopService = false); startServer() }
            else -> startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (server != null || startJob?.isActive == true) return

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("API server starting...", ""),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )

        startJob = scope.launch(Dispatchers.IO) {
            val settings = settingsRepository.current()
            if (settings.apiServerKey.isBlank()) {
                ApiServerController.setError("API server needs a bearer token before it can start.")
                stopServer()
                return@launch
            }
            val host = if (settings.apiServerAllowLan) "0.0.0.0" else "127.0.0.1"
            val port = settings.apiServerPort
            val displayHost = if (settings.apiServerAllowLan) (lanIpv4() ?: "0.0.0.0") else "127.0.0.1"

            val notification = buildNotification("API server running", "http://$displayHost:$port/v1")
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
            notificationManager?.notify(NOTIFICATION_ID, notification)

            val srv = HermesApiServer(
                hostname = host,
                port = port,
                apiKey = settings.apiServerKey,
                orchestrator = orchestrator,
                scope = scope,
                chatRepository = chatRepository,
                conversationRepository = conversationRepository,
            )
            try {
                // NanoHTTPD.start with SOCKET_READ_TIMEOUT and daemon=false so the
                // listener thread keeps the server alive alongside the service.
                server = srv
                srv.start(NanoTimeouts.SOCKET_READ_TIMEOUT, false)
                // Stopped while binding: do not leave a listener nobody can reach to stop.
                if (!isActive) {
                    runCatching { srv.stop() }
                    if (server === srv) server = null
                    return@launch
                }
                ApiServerController.setRunning(displayHost, port)
                Timber.tag("ApiServer").i("started on %s:%d (lan=%b)", host, port, settings.apiServerAllowLan)
            } catch (t: Throwable) {
                Timber.tag("ApiServer").e(t, "failed to start on port %d", port)
                ApiServerController.setError(t.message ?: "failed to start (port $port in use?)")
                // Only forget our own instance; never one another start put there.
                if (server === srv) server = null
                runCatching { srv.stop() }
                stopSelf()
            }
        }
    }

    private fun stopServer(stopService: Boolean = true) {
        startJob?.cancel()
        startJob = null
        server?.let { runCatching { it.stop() } }
        server = null
        ApiServerController.setStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Timber.tag("ApiServer").w("foreground-service time limit reached; stopping API server")
        stopServer()
    }

    /** First non-loopback IPv4 address, for showing a reachable LAN URL. */
    private fun lanIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    }.getOrNull()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hermes API Server",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Local OpenAI-compatible API server status"
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.let { runCatching { it.stop() } }
        server = null
        ApiServerController.setStopped()
        scope.cancel()
        super.onDestroy()
    }

    /** NanoHTTPD's default socket read timeout (ms). */
    private object NanoTimeouts {
        const val SOCKET_READ_TIMEOUT = 10_000
    }
}
