package com.hermes.agent.data.tools

import com.hermes.agent.data.voice.VoiceOutputEvent
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Speak text aloud. Ported from hermes-agent's `tts_tool.py`; the model sends text and the
 * phone speaks it. `action="stop"` halts any in-progress speech.
 *
 * Speech goes through [VoiceOutputManager], the platform
 * `android.speech.tts.TextToSpeech` engine, as a shared singleton — this never spins up a
 * second engine instance.
 */
@Singleton
class TtsTool @Inject constructor(
    private val voiceOutput: VoiceOutputManager,
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
        ),
        category = "communication",
        capabilities = setOf("media:tts", "communication"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = arguments["action"].str()?.trim()?.lowercase() ?: "speak"

        if (action == "stop") {
            // Idempotent: safe to call with nothing in flight.
            voiceOutput.stop()
            return ToolResult.ok("Stopped speech.", System.currentTimeMillis() - start)
        }

        val text = arguments["text"].str()?.trim()
        if (text.isNullOrEmpty()) {
            return ToolResult.error("missing required parameter: text", System.currentTimeMillis() - start)
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

@Module
@InstallIn(SingletonComponent::class)
abstract class TtsToolModule {
    @Binds
    @IntoSet
    abstract fun bindTtsTool(tool: TtsTool): Tool
}
