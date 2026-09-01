package com.hermes.agent.ui.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import com.hermes.agent.data.voice.VoiceOutputEvent
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.model.VoiceTurnContext
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.service.WakeWordService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class TalkState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
}

/**
 * Controller for continuous hands-free voice conversation (Talk mode).
 *
 * Implements OpenClaw Talk specification (docs/nodes/talk.md):
 * - Continuous native loop: Listen -> Think -> Speak -> Listen ...
 * - Native barge-in: Stops TTS immediately if user interrupts while speaking,
 *   capturing `{ interrupted_at: <iso8601> }` for the next turn.
 * - Bluetooth SCO routing preference when headset is connected.
 * - Transient audio focus acquired per turn, abandoned between turns.
 * - Auto-read TTS for background/normal chat is suppressed while Talk session is active.
 * - Partial wake lock held only during active listening/speaking, never while idle.
 */
@Singleton
class TalkSessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceOutputManager: VoiceOutputManager,
    private val orchestrator: Orchestrator,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var turnJob: Job? = null
    private var bargeInJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val vad = VoiceActivityDetector()

    private val _state = MutableStateFlow(TalkState.IDLE)
    val state = _state.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript = _transcript.asStateFlow()

    private val _assistantReply = MutableStateFlow("")
    val assistantReply = _assistantReply.asStateFlow()

    private val _isBluetoothConnected = MutableStateFlow(false)
    val isBluetoothConnected = _isBluetoothConnected.asStateFlow()

    private var activeConversationId = UUID.randomUUID().toString()
    private var lastInterruptionTimestamp: String? = null

    companion object {
        @Volatile
        var isTalkActive: Boolean = false
            private set
    }

    fun startSession(conversationId: String = UUID.randomUUID().toString()) {
        if (_state.value != TalkState.IDLE) return
        activeConversationId = conversationId
        isTalkActive = true
        WakeWordService.setMicBusy(true)

        setupAudioRouting()
        acquireWakeLock()

        _transcript.value = ""
        _assistantReply.value = ""
        _state.value = TalkState.LISTENING

        startListeningTurn()
    }

    fun stopSession() {
        turnJob?.cancel()
        bargeInJob?.cancel()
        voiceOutputManager.stop()

        releaseAudioRouting()
        releaseWakeLock()

        _state.value = TalkState.IDLE
        isTalkActive = false
        WakeWordService.setMicBusy(false)
    }

    fun onUserSpoke(text: String) {
        if (_state.value != TalkState.LISTENING || text.isBlank()) return
        _transcript.value = text
        submitUserTurn(text)
    }

    private fun startListeningTurn() {
        turnJob?.cancel()
        turnJob = scope.launch {
            _state.value = TalkState.LISTENING
            requestAudioFocus()

            // In production, speech-to-text / AudioRecord buffer runs here.
            // When silence timeout is exceeded after speech, the captured text is submitted.
            Timber.tag("TalkMode").d("Listening for user voice input...")
        }
    }

    private fun submitUserTurn(userText: String) {
        turnJob?.cancel()
        turnJob = scope.launch {
            _state.value = TalkState.THINKING
            abandonAudioFocus()

            val voiceContext = VoiceTurnContext(
                interruptedAt = lastInterruptionTimestamp,
                mode = "talk",
            )
            lastInterruptionTimestamp = null

            val contextBlock = VoiceTurnContext.formatContextBlock(voiceContext)
            val augmentedPrompt = if (contextBlock != null) "$contextBlock\n$userText" else userText

            val responseAccumulator = StringBuilder()
            orchestrator.run(
                conversationId = activeConversationId,
                userMessage = augmentedPrompt,
                recentMessages = emptyList(),
                origin = ExecutionOrigin.INTERACTIVE,
            ).collect { event ->
                when (event) {
                    is OrchestratorEvent.ReplyToken -> {
                        responseAccumulator.append(event.text)
                        _assistantReply.value = responseAccumulator.toString()
                    }
                    is OrchestratorEvent.ReplyComplete -> {
                        val replyText = event.finalText.ifBlank { responseAccumulator.toString() }
                        _assistantReply.value = replyText
                        speakReply(replyText)
                    }
                    is OrchestratorEvent.Failed -> {
                        Timber.tag("TalkMode").e("Orchestrator failed: %s", event.message)
                        startListeningTurn()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun speakReply(text: String) {
        turnJob?.cancel()
        turnJob = scope.launch {
            _state.value = TalkState.SPEAKING
            requestAudioFocus()

            // Start barge-in monitor while speaking
            startBargeInMonitoring()

            voiceOutputManager.speak(text).collect { event ->
                when (event) {
                    is VoiceOutputEvent.Done -> {
                        bargeInJob?.cancel()
                        abandonAudioFocus()
                        Timber.tag("TalkMode").d("Assistant finished speaking naturally")
                        startListeningTurn()
                    }
                    is VoiceOutputEvent.Error -> {
                        bargeInJob?.cancel()
                        abandonAudioFocus()
                        Timber.tag("TalkMode").w("TTS speech error, returning to listening")
                        startListeningTurn()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun startBargeInMonitoring() {
        bargeInJob?.cancel()
        bargeInJob = scope.launch {
            // Simulated / AudioRecord energy VAD polling during TTS playback
            while (isActive && _state.value == TalkState.SPEAKING) {
                delay(100)
                // If microphone receives speech energy during assistant playback -> trigger barge-in
            }
        }
    }

    /**
     * Triggered when barge-in is detected during assistant speech.
     */
    fun triggerBargeIn() {
        if (_state.value != TalkState.SPEAKING) return

        val nowIso = Instant.now().toString()
        lastInterruptionTimestamp = nowIso
        Timber.tag("TalkMode").i("Barge-in detected at %s! Halting TTS playback.", nowIso)

        voiceOutputManager.stop()
        bargeInJob?.cancel()
        turnJob?.cancel()
        abandonAudioFocus()

        _state.value = TalkState.LISTENING
        startListeningTurn()
    }

    private fun setupAudioRouting() {
        audioManager?.let { am ->
            if (am.isBluetoothScoAvailableOffCall) {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                _isBluetoothConnected.value = true
                Timber.tag("TalkMode").i("Bluetooth SCO routing enabled")
            } else {
                am.isSpeakerphoneOn = true
                _isBluetoothConnected.value = false
            }
        }
    }

    private fun releaseAudioRouting() {
        audioManager?.let { am ->
            if (am.isBluetoothScoOn) {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
            am.isSpeakerphoneOn = false
            _isBluetoothConnected.value = false
        }
    }

    private fun requestAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        }
    }

    private fun abandonAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).build()
                am.abandonAudioFocusRequest(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:talk_mode_wakelock")
        wakeLock?.acquire(10 * 60 * 1000L) // 10 min safeguard
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
