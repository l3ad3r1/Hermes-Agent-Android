package com.hermes.agent.data.tools

import com.hermes.agent.data.voice.VoiceOutputManager
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

/** Covers the `speak` tool, which drives the platform TextToSpeech engine. */
class TtsToolTest {

    private val voiceOutput = mockk<VoiceOutputManager>(relaxed = true)
    private val tool = TtsTool(voiceOutput)

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
    fun `speaks through the platform engine`() = runTest {
        platformSucceeds()

        val result = tool.execute(args("text" to "hello"))

        assertTrue(result.errorMessage, result.success)
        verify { voiceOutput.speak("hello", any()) }
    }

    @Test
    fun `fails cleanly when the engine is unavailable`() = runTest {
        every { voiceOutput.initialize(any()) } answers {
            firstArg<((Boolean) -> Unit)?>()?.invoke(false)
        }

        val result = tool.execute(args("text" to "hello"))

        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("unavailable"))
    }

    @Test
    fun `stop halts the engine`() = runTest {
        val result = tool.execute(args("action" to "stop"))

        assertTrue(result.success)
        verify { voiceOutput.stop() }
    }

    @Test
    fun `missing text is a tool error`() = runTest {
        val result = tool.execute(args("action" to "speak"))
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("text"))
    }
}
