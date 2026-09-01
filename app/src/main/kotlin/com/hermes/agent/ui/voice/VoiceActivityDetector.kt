package com.hermes.agent.ui.voice

import kotlin.math.sqrt

/**
 * Lightweight energy-based Voice Activity Detector (VAD) for Talk mode barge-in detection
 * and silence end-of-turn timeout calculation.
 */
class VoiceActivityDetector(
    private val speechThreshold: Float = 800.0f,
    private val silenceThreshold: Float = 300.0f,
) {
    /**
     * Computes root-mean-square (RMS) energy level of 16-bit PCM audio samples.
     */
    fun computeRms(buffer: ShortArray, readCount: Int): Double {
        if (readCount <= 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until readCount) {
            val sample = buffer[i].toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / readCount)
    }

    /**
     * Checks if the given audio frame contains human speech above the threshold.
     */
    fun isSpeech(buffer: ShortArray, readCount: Int): Boolean {
        return computeRms(buffer, readCount) >= speechThreshold
    }

    /**
     * Checks if the frame is silence below the silence threshold.
     */
    fun isSilence(buffer: ShortArray, readCount: Int): Boolean {
        return computeRms(buffer, readCount) < silenceThreshold
    }
}
