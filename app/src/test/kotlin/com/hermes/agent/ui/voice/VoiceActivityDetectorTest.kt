package com.hermes.agent.ui.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {

    private val vad = VoiceActivityDetector(speechThreshold = 500.0f, silenceThreshold = 100.0f)

    @Test
    fun silenceFrame_detectedAsSilence() {
        val silentBuffer = ShortArray(512) { 0 }
        assertTrue(vad.isSilence(silentBuffer, silentBuffer.size))
        assertFalse(vad.isSpeech(silentBuffer, silentBuffer.size))
    }

    @Test
    fun lowNoiseFrame_belowSpeechThreshold() {
        val lowNoise = ShortArray(512) { 50 }
        assertFalse(vad.isSpeech(lowNoise, lowNoise.size))
    }

    @Test
    fun speechFrame_detectedAsSpeech() {
        val speechBuffer = ShortArray(512) { 2000 }
        assertTrue(vad.isSpeech(speechBuffer, speechBuffer.size))
        assertFalse(vad.isSilence(speechBuffer, speechBuffer.size))
    }
}
