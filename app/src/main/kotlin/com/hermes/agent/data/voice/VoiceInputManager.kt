package com.hermes.agent.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Android's [SpeechRecognizer] for streaming voice input.
 *
 * Per Section 7.3 of the plan: "Voice input/output is added using
 * Android's SpeechRecognizer API for input and a lightweight TTS engine
 * for spoken responses."
 *
 * Exposes a cold Flow that:
 *   1. Starts listening when collected.
 *   2. Emits partial transcripts as [VoiceInputEvent.Partial].
 *   3. Emits the final transcript as [VoiceInputEvent.Final].
 *   4. Emits [VoiceInputEvent.Error] on failure.
 *   5. Cancels the recognizer when the Flow is cancelled (e.g. user
 *      taps the stop button).
 *
 * Requires `android.permission.RECORD_AUDIO` — declared in the manifest,
 * granted at runtime when the user first taps the mic button.
 */
@Singleton
class VoiceInputManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** True if a speech recognizer is available on this device. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Start listening. The returned Flow is cold — collection actually
     * starts the recognizer; cancellation stops it.
     */
    fun listen(locale: String = "en-US"): Flow<VoiceInputEvent> = callbackFlow {
        if (!isAvailable()) {
            trySend(VoiceInputEvent.Error("SpeechRecognizer not available on this device"))
            awaitClose { }
            return@callbackFlow
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(VoiceInputEvent.Ready)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "no speech detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech input timed out"
                    SpeechRecognizer.ERROR_AUDIO -> "audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "client-side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "RECORD_AUDIO permission not granted"
                    SpeechRecognizer.ERROR_NETWORK -> "network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
                    SpeechRecognizer.ERROR_SERVER -> "server error"
                    else -> "unknown speech recognizer error ($error)"
                }
                Timber.tag("VoiceInput").w("onError: %s", msg)
                trySend(VoiceInputEvent.Error(msg))
                channel.close()
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                trySend(VoiceInputEvent.Final(text))
                channel.close()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                if (text.isNotBlank()) trySend(VoiceInputEvent.Partial(text))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)
        // Mic is now hot — surface it process-wide so Hermes's eyes react.
        VoiceActivity.setListening(true)

        awaitClose {
            VoiceActivity.setListening(false)
            runCatching {
                recognizer.stopListening()
                recognizer.destroy()
            }
        }
    }
}

sealed class VoiceInputEvent {
    object Ready : VoiceInputEvent()
    data class Partial(val text: String) : VoiceInputEvent()
    data class Final(val text: String) : VoiceInputEvent()
    data class Error(val message: String) : VoiceInputEvent()
}
