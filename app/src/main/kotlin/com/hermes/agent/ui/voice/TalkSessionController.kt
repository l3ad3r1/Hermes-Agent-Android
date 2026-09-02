package com.hermes.agent.ui.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
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
    private val recognizer = TalkSpeechRecognizer(context)

    private val _state = MutableStateFlow(TalkState.IDLE)
    val state = _state.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript = _transcript.asStateFlow()

    private val _assistantReply = MutableStateFlow("")
    val assistantReply = _assistantReply.asStateFlow()

    private val _isBluetoothConnected = MutableStateFlow(false)
    val isBluetoothConnected = _isBluetoothConnected.asStateFlow()

    /** Surfaced to the Talk screen when a session ends for a reason the user should see. */
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var activeConversationId = UUID.randomUUID().toString()
    private var lastInterruptionTimestamp: String? = null

    companion object {
        @Volatile
        var isTalkActive: Boolean = false
            private set

        private const val VAD_SAMPLE_RATE = 16_000
        private const val VAD_FRAME_SAMPLES = 512

        /**
         * Consecutive speech frames before playback is cut. One frame is ~32 ms,
         * so this is ~100 ms of sustained speech — long enough to ignore a click
         * or the tail of the assistant's own voice through the speaker.
         */
        private const val SPEECH_FRAMES_TO_TRIGGER = 3
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
        _error.value = null
        _state.value = TalkState.LISTENING

        startListeningTurn()
    }

    fun stopSession() {
        turnJob?.cancel()
        bargeInJob?.cancel()
        recognizer.stop()
        recognizer.release()
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
        bargeInJob?.cancel()
        _state.value = TalkState.LISTENING
        requestAudioFocus()

        if (!recognizer.isAvailable) {
            Timber.tag("TalkMode").w("No speech recogniser on this device — ending Talk session")
            _error.value = "Speech recognition is unavailable on this device."
            stopSession()
            return
        }

        _transcript.value = ""
        recognizer.start(
            onSpeechStarted = { Timber.tag("TalkMode").d("User started speaking") },
            onPartial = { _transcript.value = it },
            onFinalTranscript = { text ->
                if (text.isNullOrBlank()) {
                    // Silence or an unintelligible turn: keep the session alive and
                    // listen again rather than ending it, matching OpenClaw's
                    // "Talk continues until manually stopped".
                    if (_state.value == TalkState.LISTENING) startListeningTurn()
                } else {
                    _transcript.value = text
                    submitUserTurn(text)
                }
            },
            onError = { code ->
                Timber.tag("TalkMode").w("Recogniser error %d — ending Talk session", code)
                _error.value = "Voice input stopped (recogniser error $code)."
                stopSession()
            },
        )
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

    /**
     * Barge-in: sample the mic while the assistant speaks and cut playback the
     * moment the user starts talking (OpenClaw `docs/nodes/talk.md` — "playback
     * stops and the interruption timestamp is noted for the next prompt").
     *
     * A short RMS window is enough and far cheaper than a second recogniser.
     * [SPEECH_FRAMES_TO_TRIGGER] consecutive speech frames guard against the
     * assistant's own voice leaking back through the speaker.
     */
    private fun startBargeInMonitoring() {
        bargeInJob?.cancel()
        bargeInJob = scope.launch {
            val minBuffer = AudioRecord.getMinBufferSize(
                VAD_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(VAD_FRAME_SAMPLES * 2)

            var record: AudioRecord? = null
            var detected = false
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    VAD_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Timber.tag("TalkMode").w("Barge-in VAD unavailable; assistant speech cannot be interrupted by voice")
                    return@launch
                }
                record.startRecording()

                val frame = ShortArray(VAD_FRAME_SAMPLES)
                var speechFrames = 0
                while (isActive && _state.value == TalkState.SPEAKING && !detected) {
                    val read = record.read(frame, 0, frame.size)
                    if (read > 0 && vad.isSpeech(frame, read)) {
                        speechFrames++
                        if (speechFrames >= SPEECH_FRAMES_TO_TRIGGER) detected = true
                    } else {
                        speechFrames = 0
                    }
                    delay(30)
                }
            } catch (t: Throwable) {
                Timber.tag("TalkMode").w(t, "Barge-in monitor failed; continuing without voice interruption")
            } finally {
                // Release the mic *before* handing it to the recogniser — starting
                // SpeechRecognizer while this AudioRecord still holds VOICE_COMMUNICATION
                // fails with ERROR_RECOGNIZER_BUSY on most devices.
                runCatching {
                    if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                    record?.release()
                }
            }

            if (detected) triggerBargeIn()
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
            // isBluetoothScoAvailableOffCall is true on any device that *supports*
            // SCO, connected or not — routing on that alone lit the "Bluetooth
            // headset" banner with nothing paired. Only take the SCO path when a
            // BT audio output device is actually connected.
            if (hasConnectedBluetoothAudioDevice(am) && am.isBluetoothScoAvailableOffCall) {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                _isBluetoothConnected.value = true
                Timber.tag("TalkMode").i("Bluetooth SCO routing enabled")
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
                _isBluetoothConnected.value = false
            }
        }
    }

    private fun hasConnectedBluetoothAudioDevice(am: AudioManager): Boolean =
        runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }.getOrDefault(false)

    private fun releaseAudioRouting() {
        audioManager?.let { am ->
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoOn) {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
            @Suppress("DEPRECATION")
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
