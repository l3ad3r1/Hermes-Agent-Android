package com.hermes.agent.data.tools

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speak text aloud through the device's text-to-speech engine. Ported from
 * hermes-agent's `tts_tool.py`, using Android's native, on-device
 * [TextToSpeech] as the provider — free, no API key, works offline once the
 * engine's voice data is installed.
 *
 * The model just sends text; the phone speaks it. `action="stop"` halts any
 * in-progress speech. The engine is initialised lazily on first use and kept
 * alive for the process lifetime (this is a [Singleton]).
 */
@Singleton
class TtsTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {

    private val initMutex = Mutex()
    @Volatile private var engine: TextToSpeech? = null

    override val descriptor = ToolDescriptor(
        name = "speak",
        description = "Speak text aloud through the device speaker using on-device text-to-speech. " +
            "Use this when the user asks you to say, read out, or announce something, or when a " +
            "spoken response is more useful than text (hands-free / accessibility). action='speak' " +
            "(default) reads the `text`; action='stop' halts any current speech. Optional rate and " +
            "pitch default to 1.0 (range ~0.5–2.0).",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "speak (default) or stop.",
                required = false,
                enumValues = listOf("speak", "stop"),
            ),
            ToolParameter(
                name = "text",
                type = ToolParameterType.STRING,
                description = "The text to speak. Required for action='speak'.",
                required = false,
            ),
            ToolParameter(
                name = "rate",
                type = ToolParameterType.NUMBER,
                description = "Speech rate multiplier, 1.0 = normal. Optional.",
                required = false,
            ),
            ToolParameter(
                name = "pitch",
                type = ToolParameterType.NUMBER,
                description = "Voice pitch multiplier, 1.0 = normal. Optional.",
                required = false,
            ),
        ),
        category = "communication",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = arguments["action"].str()?.trim()?.lowercase() ?: "speak"

        val tts = try {
            obtainEngine()
        } catch (t: Throwable) {
            return ToolResult.error(
                t.message ?: "text-to-speech engine unavailable on this device",
                System.currentTimeMillis() - start,
            )
        }

        if (action == "stop") {
            tts.stop()
            return ToolResult.ok("Stopped speech.", System.currentTimeMillis() - start)
        }

        val text = arguments["text"].str()?.trim()
        if (text.isNullOrEmpty()) {
            return ToolResult.error("missing required parameter: text", System.currentTimeMillis() - start)
        }

        tts.setSpeechRate((arguments["rate"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 1.0f)
        tts.setPitch((arguments["pitch"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 1.0f)

        return try {
            speakAndAwait(tts, text)
            ToolResult.ok("Spoke aloud: \"$text\"", System.currentTimeMillis() - start)
        } catch (e: TimeoutCancellationException) {
            // Speech is still playing; we just stopped waiting on it.
            ToolResult.ok("Speaking (long utterance still playing).", System.currentTimeMillis() - start)
        } catch (t: Throwable) {
            ToolResult.error(t.message ?: "failed to speak", System.currentTimeMillis() - start)
        }
    }

    /** Lazily build (once) and return the initialised engine. */
    private suspend fun obtainEngine(): TextToSpeech {
        engine?.let { return it }
        return initMutex.withLock {
            engine?.let { return it }
            val ready = CompletableDeferred<Int>()
            val tts = TextToSpeech(context) { status -> ready.complete(status) }
            val status = withTimeout(INIT_TIMEOUT_MS) { ready.await() }
            if (status != TextToSpeech.SUCCESS) {
                tts.shutdown()
                throw IllegalStateException("text-to-speech engine failed to initialise")
            }
            tts.language = Locale.getDefault()
            engine = tts
            tts
        }
    }

    /** Speak [text] and suspend until the utterance finishes (or times out). */
    private suspend fun speakAndAwait(tts: TextToSpeech, text: String) {
        val targetId = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == targetId) done.complete(Unit)
            }
            @Deprecated("deprecated in API 21")
            override fun onError(utteranceId: String?) {
                done.completeExceptionally(IllegalStateException("speech synthesis error"))
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                done.completeExceptionally(IllegalStateException("speech synthesis error $errorCode"))
            }
        })
        val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, targetId)
        if (queued != TextToSpeech.SUCCESS) throw IllegalStateException("engine rejected the utterance")
        withTimeout(SPEAK_TIMEOUT_MS) { done.await() }
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val INIT_TIMEOUT_MS = 5_000L
        const val SPEAK_TIMEOUT_MS = 30_000L
    }
}
