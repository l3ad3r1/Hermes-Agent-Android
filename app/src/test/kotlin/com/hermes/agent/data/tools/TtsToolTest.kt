package com.hermes.agent.data.tools

import com.hermes.agent.data.voice.VoiceOutputManager
import com.sassybutler.alarm.ButlerSpeech
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two-engine `speak` tool: Butler's ONNX voice from `:feature:butler` first,
 * platform TextToSpeech as the fallback.
 */
class TtsToolTest {

    private val voiceOutput = mockk<VoiceOutputManager>(relaxed = true)
    private val butlerSpeech = mockk<ButlerSpeech>(relaxed = true)
    private val tool = TtsTool(voiceOutput, butlerSpeech)

    private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { it.first to JsonPrimitive(it.second) }

    /** Make the platform engine report ready and complete an utterance with no error. */
    private fun platformSucceeds() {
        every { voiceOutput.initialize(any()) } answers {
            firstArg<((Boolean) -> Unit)?>()?.invoke(true)
        }
        every { voiceOutput.speak(any(), any()) } returns flowOf()
    }

    @Test
    fun `speaks with Butler's voice by default and never touches the platform engine`() = runTest {
        coEvery { butlerSpeech.speak(any(), any()) } returns ButlerSpeech.SpeakResult.SPOKEN

        val result = tool.execute(args("text" to "good morning"))

        assertTrue(result.errorMessage, result.success)
        assertTrue(result.output, result.output.contains("Butler"))
        coVerify { butlerSpeech.speak("good morning", any()) }
        verify(exactly = 0) { voiceOutput.speak(any(), any()) }
    }

    @Test
    fun `falls back to the platform engine when the ONNX model cannot speak`() = runTest {
        coEvery { butlerSpeech.speak(any(), any()) } returns ButlerSpeech.SpeakResult.UNAVAILABLE
        platformSucceeds()

        val result = tool.execute(args("text" to "hello"))

        assertTrue(result.errorMessage, result.success)
        assertFalse("should not claim Butler's voice on fallback", result.output.contains("Butler"))
        verify { voiceOutput.speak("hello", any()) }
    }

    /**
     * A stop() that lands while Butler is synthesizing must NOT route the same text to the
     * platform engine: the user silenced this utterance, and "falling back" would speak it
     * anyway. Only UNAVAILABLE may fall through.
     */
    @Test
    fun `a stop during Butler synthesis does not fall back to the platform engine`() = runTest {
        coEvery { butlerSpeech.speak(any(), any()) } returns ButlerSpeech.SpeakResult.STOPPED
        platformSucceeds()

        val result = tool.execute(args("text" to "hello"))

        assertTrue(result.errorMessage, result.success)
        assertTrue(result.output, result.output.contains("stopped", ignoreCase = true))
        verify(exactly = 0) { voiceOutput.speak(any(), any()) }
    }

    @Test
    fun `voice=system bypasses Butler entirely`() = runTest {
        platformSucceeds()

        val result = tool.execute(args("text" to "hello", "voice" to "system"))

        assertTrue(result.errorMessage, result.success)
        coVerify(exactly = 0) { butlerSpeech.speak(any(), any()) }
        verify { voiceOutput.speak("hello", any()) }
    }

    @Test
    fun `both engines fail cleanly when neither is available`() = runTest {
        coEvery { butlerSpeech.speak(any(), any()) } returns ButlerSpeech.SpeakResult.UNAVAILABLE
        every { voiceOutput.initialize(any()) } answers {
            firstArg<((Boolean) -> Unit)?>()?.invoke(false)
        }

        val result = tool.execute(args("text" to "hello"))

        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("unavailable"))
    }

    @Test
    fun `stop halts both engines`() = runTest {
        val result = tool.execute(args("action" to "stop"))

        assertTrue(result.success)
        verify { butlerSpeech.stop() }
        verify { voiceOutput.stop() }
    }

    @Test
    fun `missing text is a tool error`() = runTest {
        val result = tool.execute(args("action" to "speak"))
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("text"))
    }
}
