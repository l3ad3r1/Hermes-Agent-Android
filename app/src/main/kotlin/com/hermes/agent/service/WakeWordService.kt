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
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service for on-device wake-word detection ("Hey Hermes").
 *
 * Implements OpenClaw voicewake specification (docs/nodes/voicewake.md):
 * - On-device keyword spotting via the platform [SpeechRecognizer]. The recogniser
 *   is asked to run on-device (`createOnDeviceSpeechRecognizer` on API 31+, else
 *   `EXTRA_PREFER_OFFLINE`); no model is bundled and no audio leaves the device.
 * - Transcript hypotheses are matched against the configured trigger phrases with
 *   [WakeWordConfig.matchTrigger] — a real phrase match, not an audio-energy gate.
 * - Off by default; shows a persistent notification with a 1-tap disable action.
 * - Suspends recognition while the microphone is held by Talk mode or speech-to-text.
 * - Enforces a battery floor: stops at <= 15% (unplugged) or when Battery Saver is on.
 * - Never stores or transmits audio; the recogniser is cancelled and destroyed on stop.
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var productIdentity: ProductIdentity

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var settingsJob: Job? = null
    private var micBusyJob: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null

    private var recognizer: SpeechRecognizer? = null
    private var recognitionRunning = false
    private var stopping = false
    private var recognitionUnavailable = false
    private var inCooldownUntil = 0L

    @Volatile private var currentTriggers: List<String> = listOf(WakeWordConfig.DEFAULT_TRIGGER)
    @Volatile private var currentRoutingRules: Map<String, String> = emptyMap()

    companion object {
        const val CHANNEL_ID = "wake_word_service_channel"
        const val NOTIFICATION_ID = 2005
        const val ACTION_START = "com.hermes.agent.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.hermes.agent.action.STOP_WAKE_WORD"
        const val ACTION_WAKE_WORD_TRIGGERED = "com.hermes.agent.action.WAKE_WORD_TRIGGERED"
        const val EXTRA_MATCHED_TRIGGER = "extra_matched_trigger"
        const val EXTRA_TARGET_AGENT = "extra_target_agent"

        /** Milliseconds to wait after a successful match before listening again. */
        private const val MATCH_COOLDOWN_MS = 2_000L

        /** Backoff before restarting the recogniser after a transient error. */
        private const val ERROR_BACKOFF_MS = 900L

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

        /**
         * Pure matcher used by the recogniser callback and by unit tests.
         * Returns `matchedTrigger to targetAgent`, or null when no hypothesis
         * contains a configured trigger phrase.
         */
        fun evaluate(
            hypotheses: List<String>,
            triggers: List<String>,
            routingRules: Map<String, String>,
        ): Pair<String, String>? {
            for (hypothesis in hypotheses) {
                val matched = WakeWordConfig.matchTrigger(hypothesis, triggers) ?: continue
                return matched to WakeWordConfig.resolveTargetAgent(matched, routingRules)
            }
            return null
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
                teardown()
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
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startListeningAsForeground() {
        val notification = buildForegroundNotification(
            if (recognitionUnavailable) {
                "Speech recognition is unavailable on this device"
            } else {
                "Say \"${primaryTriggerLabel()}\" to start a voice turn"
            },
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        stopping = false
        _isListening.value = true

        if (isBatteryFloorReached()) {
            Timber.tag("WakeWord").w("WakeWordService stopping due to battery floor (<=15% or battery saver)")
            teardown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (settingsJob == null) {
            settingsJob = scope.launch {
                settingsRepository.observe().collectLatest { settings ->
                    if (!settings.wakeWordEnabled) {
                        Timber.tag("WakeWord").i("Wake word disabled in settings — stopping service")
                        teardown()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    currentTriggers = settings.wakeWordTriggers.ifEmpty { listOf(WakeWordConfig.DEFAULT_TRIGGER) }
                    currentRoutingRules = settings.wakeWordRoutingRules
                    ensureRecognitionRunning()
                }
            }
        }

        if (micBusyJob == null) {
            micBusyJob = scope.launch {
                _isMicBusy.collectLatest { busy ->
                    if (busy) {
                        mainHandler.post { cancelRecognition() }
                    } else if (_isListening.value && !stopping) {
                        ensureRecognitionRunning()
                    }
                }
            }
        }

        ensureRecognitionRunning()
    }

    private fun teardown() {
        stopping = true
        settingsJob?.cancel(); settingsJob = null
        micBusyJob?.cancel(); micBusyJob = null
        mainHandler.post { destroyRecognizer() }
        _isListening.value = false
    }

    private fun ensureRecognitionRunning() {
        mainHandler.post {
            if (stopping || recognitionRunning || recognitionUnavailable) return@post
            if (_isMicBusy.value) return@post
            if (System.currentTimeMillis() < inCooldownUntil) {
                mainHandler.postDelayed({ ensureRecognitionRunning() }, inCooldownUntil - System.currentTimeMillis())
                return@post
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                markRecognitionUnavailable()
                return@post
            }
            val rec = recognizer ?: createRecognizer()?.also {
                it.setRecognitionListener(recognitionListener)
                recognizer = it
            }
            if (rec == null) {
                markRecognitionUnavailable()
                return@post
            }
            recognitionRunning = true
            runCatching { rec.startListening(buildRecognizerIntent()) }
                .onFailure {
                    Timber.tag("WakeWord").w(it, "startListening failed")
                    recognitionRunning = false
                    scheduleRestart(ERROR_BACKOFF_MS)
                }
        }
    }

    private fun cancelRecognition() {
        recognitionRunning = false
        runCatching { recognizer?.cancel() }
    }

    private fun destroyRecognizer() {
        recognitionRunning = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.postDelayed({
            recognitionRunning = false
            ensureRecognitionRunning()
        }, delayMs)
    }

    private fun markRecognitionUnavailable() {
        if (recognitionUnavailable) return
        recognitionUnavailable = true
        Timber.tag("WakeWord").w("On-device speech recognition unavailable — wake word cannot run")
        updateNotification("Speech recognition is unavailable on this device")
    }

    private fun createRecognizer(): SpeechRecognizer? = try {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            SpeechRecognizer.isRecognitionAvailable(this) ->
                SpeechRecognizer.createSpeechRecognizer(this)
            else -> null
        }
    } catch (t: Throwable) {
        Timber.tag("WakeWord").w(t, "Could not create SpeechRecognizer")
        null
    }

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            handleHypotheses(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
        }

        override fun onResults(results: Bundle?) {
            val consumed = handleHypotheses(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            recognitionRunning = false
            if (!consumed) ensureRecognitionRunning()
        }

        override fun onError(error: Int) {
            recognitionRunning = false
            when (error) {
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                -> {
                    mainHandler.post { destroyRecognizer() }
                    scheduleRestart(ERROR_BACKOFF_MS)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Timber.tag("WakeWord").w("RECORD_AUDIO permission missing — stopping wake word")
                    mainHandler.post {
                        teardown()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                else -> scheduleRestart(if (error == SpeechRecognizer.ERROR_NO_MATCH) 0L else ERROR_BACKOFF_MS)
            }
        }
    }

    /** @return true when a trigger matched (recognition should pause for cooldown). */
    private fun handleHypotheses(hypotheses: ArrayList<String>?): Boolean {
        if (hypotheses.isNullOrEmpty()) return false
        val match = evaluate(hypotheses, currentTriggers, currentRoutingRules) ?: return false
        Timber.tag("WakeWord").i("Wake word matched: '%s' -> target agent: %s", match.first, match.second)
        inCooldownUntil = System.currentTimeMillis() + MATCH_COOLDOWN_MS
        cancelRecognition()
        onWakeWordMatched(match.first, match.second)
        scheduleRestart(MATCH_COOLDOWN_MS)
        return true
    }

    private fun primaryTriggerLabel(): String =
        currentTriggers.firstOrNull()?.takeIf { it.isNotBlank() } ?: WakeWordConfig.DEFAULT_TRIGGER

    private fun onWakeWordMatched(trigger: String, targetAgent: String) {
        val broadcastIntent = Intent(ACTION_WAKE_WORD_TRIGGERED).apply {
            setPackage(packageName)
            putExtra(EXTRA_MATCHED_TRIGGER, trigger)
            putExtra(EXTRA_TARGET_AGENT, targetAgent)
        }
        sendBroadcast(broadcastIntent)

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
                    teardown()
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

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }

    private fun buildForegroundNotification(contentText: String): Notification {
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
            .setContentText(contentText)
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
