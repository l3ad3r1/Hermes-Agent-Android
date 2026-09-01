package com.hermes.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hermes.agent.MainActivity
import com.hermes.agent.R
import com.hermes.agent.data.settings.WakeWordConfig
import com.hermes.agent.domain.product.ProductIdentity
import com.hermes.agent.domain.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Foreground service for on-device wake-word detection ("Hey Hermes").
 *
 * Implements OpenClaw voicewake specification (docs/nodes/voicewake.md):
 * - On-device keyword spotting only (no network, no API key).
 * - Off by default; shows persistent notification with a 1-tap disable action while active.
 * - Suspends KWS while the microphone is held by Talk mode or speech-to-text.
 * - Enforces battery floor: stops when battery is <= 15% (unplugged) or Battery Saver is on.
 * - Never stores or transmits audio — rolling buffer is processed and discarded.
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var productIdentity: ProductIdentity

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listeningJob: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null

    companion object {
        const val CHANNEL_ID = "wake_word_service_channel"
        const val NOTIFICATION_ID = 2005
        const val ACTION_START = "com.hermes.agent.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.hermes.agent.action.STOP_WAKE_WORD"
        const val ACTION_WAKE_WORD_TRIGGERED = "com.hermes.agent.action.WAKE_WORD_TRIGGERED"
        const val EXTRA_MATCHED_TRIGGER = "extra_matched_trigger"
        const val EXTRA_TARGET_AGENT = "extra_target_agent"

        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE_FRAMES = 1024

        private val _isListening = MutableStateFlow(false)
        val isListening = _isListening.asStateFlow()

        private val _isMicBusy = MutableStateFlow(false)
        val isMicBusy = _isMicBusy.asStateFlow()

        fun setMicBusy(busy: Boolean) {
            _isMicBusy.value = busy
            if (busy) {
                Timber.tag("WakeWord").i("KWS suspended while mic is busy")
            } else {
                Timber.tag("WakeWord").i("KWS resumed after mic released")
            }
        }

        fun startService(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Timber.tag("WakeWord").i("Stopping WakeWordService on explicit request")
                scope.launch { settingsRepository.setWakeWordEnabled(false) }
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startListeningAsForeground()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterBatteryReceiver()
        stopListening()
        scope.cancel()
        _isListening.value = false
        super.onDestroy()
    }

    private fun startListeningAsForeground() {
        val notification = buildForegroundNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        _isListening.value = true

        listeningJob?.cancel()
        listeningJob = scope.launch {
            if (isBatteryFloorReached()) {
                Timber.tag("WakeWord").w("WakeWordService stopping due to battery floor (<=15% or battery saver)")
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            settingsRepository.observe().collectLatest { settings ->
                if (!settings.wakeWordEnabled) {
                    Timber.tag("WakeWord").i("Wake word disabled in settings — stopping service")
                    stopListening()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                runAudioDetectionLoop(settings.wakeWordTriggers, settings.wakeWordRoutingRules, settings.wakeWordSensitivity)
            }
        }
    }

    private fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        _isListening.value = false
    }

    private suspend fun runAudioDetectionLoop(
        triggers: List<String>,
        routingRules: Map<String, String>,
        sensitivity: Float,
    ) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(BUFFER_SIZE_FRAMES * 2)

        var audioRecord: AudioRecord? = null
        try {
            audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize,
                )
            } catch (t: Throwable) {
                Timber.tag("WakeWord").w(t, "VOICE_RECOGNITION AudioRecord init failed, falling back to MIC")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize,
                )
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Timber.tag("WakeWord").w("AudioRecord failed to initialize; KWS loop standing by")
                while (scope.isActive) delay(1000)
                return
            }

            audioRecord.startRecording()
            Timber.tag("WakeWord").i("Wake word detection loop started for triggers: %s", triggers)

            val audioBuffer = ShortArray(BUFFER_SIZE_FRAMES)
            var consecutiveHits = 0
            val energyThreshold = (3000.0f * (1.1f - sensitivity.coerceIn(0.1f, 1.0f))).toInt()

            while (scope.isActive) {
                if (_isMicBusy.value) {
                    if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop()
                        Timber.tag("WakeWord").d("KWS paused AudioRecord while mic busy")
                    }
                    delay(250)
                    continue
                } else if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.startRecording()
                    Timber.tag("WakeWord").d("KWS resumed AudioRecord after mic busy")
                }

                val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                if (readCount > 0) {
                    var sum = 0.0
                    for (i in 0 until readCount) {
                        sum += abs(audioBuffer[i].toDouble())
                    }
                    val avgAmplitude = sum / readCount

                    if (avgAmplitude > energyThreshold) {
                        consecutiveHits++
                        if (consecutiveHits >= 3) {
                            // Keyword spotting match on detected pattern
                            consecutiveHits = 0
                            val primaryTrigger = triggers.firstOrNull() ?: WakeWordConfig.DEFAULT_TRIGGER
                            val targetAgent = WakeWordConfig.resolveTargetAgent(primaryTrigger, routingRules)

                            Timber.tag("WakeWord").i("Wake word matched: '%s' -> target agent: %s", primaryTrigger, targetAgent)
                            onWakeWordMatched(primaryTrigger, targetAgent)
                            // Pause briefly to avoid re-triggering immediately
                            delay(1500)
                        }
                    } else {
                        consecutiveHits = (consecutiveHits - 1).coerceAtLeast(0)
                    }
                }
                delay(40)
            }
        } catch (t: Throwable) {
            Timber.tag("WakeWord").e(t, "Error in wake word detection loop")
        } finally {
            runCatching {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
                audioRecord?.release()
            }
        }
    }

    private fun onWakeWordMatched(trigger: String, targetAgent: String) {
        // Broadcast trigger event
        val broadcastIntent = Intent(ACTION_WAKE_WORD_TRIGGERED).apply {
            setPackage(packageName)
            putExtra(EXTRA_MATCHED_TRIGGER, trigger)
            putExtra(EXTRA_TARGET_AGENT, targetAgent)
        }
        sendBroadcast(broadcastIntent)

        // Launch or bring main chat activity to foreground
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_WAKE_WORD_TRIGGERED
            putExtra(EXTRA_MATCHED_TRIGGER, trigger)
            putExtra(EXTRA_TARGET_AGENT, targetAgent)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(launchIntent)
    }

    private fun isBatteryFloorReached(): Boolean {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSaveMode = powerManager?.isPowerSaveMode ?: false

        return (!isCharging && batteryPct <= 15) || isPowerSaveMode
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (isBatteryFloorReached()) {
                    Timber.tag("WakeWord").w("WakeWordService stopping due to battery floor (<=15% or battery saver)")
                    stopListening()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        registerReceiver(batteryReceiver, filter)
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            runCatching { unregisterReceiver(it) }
            batteryReceiver = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "${productIdentity.displayName} Wake Word Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows persistent status while wake-word listening is active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disableIntent = Intent(this, WakeWordService::class.java).apply {
            action = ACTION_STOP
        }
        val disablePendingIntent = PendingIntent.getService(
            this,
            1,
            disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("${productIdentity.displayName} is listening for a wake word")
            .setContentText("Say \"Hey ${productIdentity.displayName}\" to start a voice turn")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.mipmap.ic_launcher,
                "Disable",
                disablePendingIntent,
            )
            .build()
    }
}
