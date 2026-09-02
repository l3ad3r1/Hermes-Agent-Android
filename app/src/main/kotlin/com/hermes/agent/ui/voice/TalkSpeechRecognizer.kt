package com.hermes.agent.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import timber.log.Timber

/**
 * Thin wrapper around the platform [SpeechRecognizer] for Talk mode.
 *
 * Talk needs three things the raw API makes awkward: on-device/offline preference,
 * a single "final transcript" callback per turn, and a *speech started* signal that
 * can fire while the assistant is still speaking (barge-in). This wraps all three
 * and keeps every recogniser call on the main looper, which the API requires.
 *
 * Callers own the lifecycle: [start] one listening turn, [stop] to abandon it,
 * [release] when the session ends.
 */
class TalkSpeechRecognizer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** True when the device has no recogniser at all — Talk cannot listen. */
    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * @param onSpeechStarted fires the moment the mic hears speech. Used for barge-in.
     * @param onFinalTranscript fires once with the best hypothesis, or null on no-match.
     * @param onError fires for a genuine failure (not "no match"/"timeout").
     */
    fun start(
        onSpeechStarted: () -> Unit,
        onPartial: (String) -> Unit = {},
        onFinalTranscript: (String?) -> Unit,
        onError: (Int) -> Unit = {},
    ) {
        mainHandler.post {
            if (listening) return@post
            if (!isAvailable) {
                Timber.tag("TalkMode").w("Speech recognition unavailable on this device")
                onError(SpeechRecognizer.ERROR_CLIENT)
                return@post
            }
            val rec = recognizer ?: createRecognizer()?.also { recognizer = it } ?: run {
                onError(SpeechRecognizer.ERROR_CLIENT)
                return@post
            }
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() = onSpeechStarted()
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(onPartial)
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    onFinalTranscript(
                        results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() },
                    )
                }

                override fun onError(error: Int) {
                    listening = false
                    // No-match and timeout are ordinary end-of-turn outcomes in a
                    // continuous loop, not failures — report them as an empty turn.
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        onFinalTranscript(null)
                    } else {
                        onError(error)
                    }
                }
            })
            listening = true
            runCatching { rec.startListening(buildIntent()) }
                .onFailure {
                    listening = false
                    Timber.tag("TalkMode").w(it, "startListening failed")
                    onError(SpeechRecognizer.ERROR_CLIENT)
                }
        }
    }

    fun stop() {
        mainHandler.post {
            listening = false
            runCatching { recognizer?.cancel() }
        }
    }

    fun release() {
        mainHandler.post {
            listening = false
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    private fun createRecognizer(): SpeechRecognizer? = try {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else -> SpeechRecognizer.createSpeechRecognizer(context)
        }
    } catch (t: Throwable) {
        Timber.tag("TalkMode").w(t, "Could not create SpeechRecognizer")
        null
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
}
