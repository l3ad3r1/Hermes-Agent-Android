package com.hermes.agent.data.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Android's [TextToSpeech] engine for spoken agent replies.
 *
 * Per Section 7.3 of the plan: "a lightweight TTS engine for spoken
 * responses."
 *
 * Exposes a cold Flow per [speak] call that completes when the engine
 * finishes speaking (or errors out). The chat UI uses this to toggle
 * the speaker icon between "playing" and "idle" states.
 *
 * The engine is lazily initialized on the first [speak] call and held
 * for the lifetime of the singleton. [shutdown] releases native
 * resources — typically called from [com.hermes.agent.HermesApp.onTerminate].
 */
@Singleton
class VoiceOutputManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready: Boolean = false

    /** True if a TTS engine is available on this device. */
    fun isAvailable(): Boolean = tts != null && ready

    /**
     * Initialize the TTS engine. Safe to call multiple times.
     * Returns true once the engine reports [TextToSpeech.SUCCESS].
     */
    fun initialize(onReady: ((Boolean) -> Unit)? = null) {
        if (tts != null) {
            onReady?.invoke(ready)
            return
        }
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.US
                Timber.tag("VoiceOutput").i("TTS engine ready")
            } else {
                Timber.tag("VoiceOutput").w("TTS init failed: status=%d", status)
            }
            onReady?.invoke(ready)
        }
    }

    /**
     * Speak the given text. Returns a Flow that emits [VoiceOutputEvent.Start]
     * immediately, [VoiceOutputEvent.Done] when the engine finishes, or
     * [VoiceOutputEvent.Error] on failure.
     */
    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()): Flow<VoiceOutputEvent> = callbackFlow {
        val engine = tts
        if (engine == null || !ready) {
            trySend(VoiceOutputEvent.Error("TTS engine not ready"))
            awaitClose { }
            return@callbackFlow
        }
        if (text.isBlank()) {
            trySend(VoiceOutputEvent.Done)
            awaitClose { }
            return@callbackFlow
        }

        trySend(VoiceOutputEvent.Start)

        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                trySend(VoiceOutputEvent.Done)
                channel.close()
            }
            override fun onError(id: String?) {
                trySend(VoiceOutputEvent.Error("TTS playback failed"))
                channel.close()
            }
        })

        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        awaitClose {
            engine.stop()
        }
    }

    /** Stop any in-progress speech. */
    fun stop() {
        tts?.stop()
    }

    /** Release native resources. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

sealed class VoiceOutputEvent {
    object Start : VoiceOutputEvent()
    object Done : VoiceOutputEvent()
    data class Error(val message: String) : VoiceOutputEvent()
}
