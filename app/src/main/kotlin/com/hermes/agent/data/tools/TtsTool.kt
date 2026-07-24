package com.hermes.agent.data.tools

import com.hermes.agent.data.voice.VoiceOutputEvent
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import com.sassybutler.alarm.ButlerSpeech
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Speak text aloud. Ported from hermes-agent's `tts_tool.py`; the model sends text and the
 * phone speaks it. `action="stop"` halts any in-progress speech.
 *
 * Two engines, in preference order:
 *  1. [ButlerSpeech] — Sassy Butler's on-device Kokoro/ONNX voice from `:feature:butler`.
 *     Far more natural than the platform engine. Its ~92 MB model loads lazily on first use,
 *     so the very first spoken reply takes a few seconds.
 *  2. [VoiceOutputManager] — the platform `android.speech.tts.TextToSpeech`, used when the
 *     ONNX model is unavailable (assets stripped from the build, session failed to create) or
 *     when the caller explicitly asks for `voice='system'`.
 *
 * Both are shared singletons; neither spins up a second engine instance.
 */
@Singleton
class TtsTool @Inject constructor(
    private val voiceOutput: VoiceOutputManager,
    private val butlerSpeech: ButlerSpeech,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "speak",
        description = "Speak text aloud through the device speaker using on-device text-to-speech. " +
            "Use this when the user asks you to say, read out, or announce something, or when a " +
            "spoken response is more useful than text (hands-free / accessibility). action='speak' " +
            "(default) reads the `text`; action='stop' halts any current speech.",
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
                name = "voice",
                type = ToolParameterType.STRING,
                description = "Which engine to speak with. 'butler' (default) uses the natural " +
                    "on-device Kokoro voice; 'system' uses the platform text-to-speech engine.",
                required = false,
                enumValues = listOf("butler", "system"),
            ),
        ),
        category = "communication",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = arguments["action"].str()?.trim()?.lowercase() ?: "speak"

        if (action == "stop") {
            // Either engine may be mid-utterance; stopping both is idempotent.
            butlerSpeech.stop()
            voiceOutput.stop()
            return ToolResult.ok("Stopped speech.", System.currentTimeMillis() - start)
        }

        val text = arguments["text"].str()?.trim()
        if (text.isNullOrEmpty()) {
            return ToolResult.error("missing required parameter: text", System.currentTimeMillis() - start)
        }

        // Butler's ONNX voice first, unless the caller asked for the platform engine.
        // speak() suspends until playback finishes. Only UNAVAILABLE falls through to the
        // platform engine — STOPPED means the user silenced this utterance mid-flight, and
        // re-speaking it with another engine is exactly what they asked us not to do.
        val requested = arguments["voice"].str()?.trim()?.lowercase() ?: "butler"
        if (requested != "system") {
            when (butlerSpeech.speak(text)) {
                ButlerSpeech.SpeakResult.SPOKEN ->
                    return ToolResult.ok("Spoke aloud in Butler's voice: \"$text\"", System.currentTimeMillis() - start)
                ButlerSpeech.SpeakResult.STOPPED ->
                    return ToolResult.ok("Speech was stopped before completion.", System.currentTimeMillis() - start)
                ButlerSpeech.SpeakResult.UNAVAILABLE -> Unit // fall through to the platform engine
            }
        }

        if (!ensureReady()) {
            return ToolResult.error(
                "text-to-speech engine unavailable on this device",
                System.currentTimeMillis() - start,
            )
        }

        // Suspend until the engine finishes the utterance (or reports an error).
        var error: String? = null
        voiceOutput.speak(text).collect { event ->
            if (event is VoiceOutputEvent.Error) error = event.message
        }

        return if (error == null) {
            ToolResult.ok("Spoke aloud: \"$text\"", System.currentTimeMillis() - start)
        } else {
            ToolResult.error(error!!, System.currentTimeMillis() - start)
        }
    }

    /** Initialise the shared engine (idempotent) and await its readiness. */
    private suspend fun ensureReady(): Boolean =
        withTimeoutOrNull(INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                voiceOutput.initialize { ready -> if (cont.isActive) cont.resume(ready) }
            }
        } ?: false

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val INIT_TIMEOUT_MS = 5_000L
    }
}
