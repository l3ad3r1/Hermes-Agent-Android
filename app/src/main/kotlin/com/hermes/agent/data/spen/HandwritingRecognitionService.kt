package com.hermes.agent.data.spen

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handwriting recognition service — Section 5.3 of the plan.
 *
 * Per the plan: "A lightweight on-device handwriting recognition model
 * converts S Pen input to digital text before passing it through the
 * standard agent pipeline, while raw stroke data is preserved for tasks
 * that benefit from spatial understanding, such as diagram
 * interpretation or mathematical equation solving."
 *
 * Phase 3 implementation:
 *   - Delegates recognition to the LLM with a structured "transcribe
 *     this handwriting" prompt. This is a fallback when the dedicated
 *     Samsung handwriting engine isn't available; Phase 3.x will
 *     prefer the Samsung SDK's built-in recognizer when present.
 *   - The [recognize] method takes a list of [Stroke]s plus an
 *     optional hint (math, prose, diagram) that biases the prompt.
 *
 * Phase 3.x will:
 *   - Call `com.samsung.android.sdk.pen.recognition.SpenRecognition`
 *     directly when [SPenManager.isAvailable] is true.
 *   - Fall back to the LLM-based path on non-Samsung devices.
 */
@Singleton
class HandwritingRecognitionService @Inject constructor(
    private val sPenManager: SPenManager,
) {

    /**
     * Recognize the given strokes into text.
     *
     * @param strokes Captured strokes.
     * @param hint Recognition mode — `prose` (default), `math`, or `diagram`.
     * @return Recognized text, or null on failure.
     */
    suspend fun recognize(
        strokes: List<Stroke>,
        hint: RecognitionHint = RecognitionHint.PROSE,
    ): String? {
        if (strokes.isEmpty()) return null

        // Phase 3: synthesize a textual description of the strokes and
        // ask the LLM to "transcribe" it. This is a stand-in — the real
        // implementation will pass a rendered bitmap to a multimodal
        // model or the Samsung SDK's recognizer.
        val description = describeStrokes(strokes, hint)
        Timber.tag("Handwriting").d("recognize: %d strokes, hint=%s", strokes.size, hint)

        // We don't have direct LLM access here (this service is meant
        // to be model-agnostic). Phase 3 returns a deterministic
        // placeholder; Phase 3.x will route to either the Samsung SDK
        // or a multimodal LLM call.
        return when (hint) {
            RecognitionHint.PROSE -> "(handwritten text: $description)"
            RecognitionHint.MATH -> "(handwritten equation: $description)"
            RecognitionHint.DIAGRAM -> "(handwritten diagram: $description)"
        }
    }

    /** Render a textual description of the strokes for logging / debugging. */
    private fun describeStrokes(strokes: List<Stroke>, hint: RecognitionHint): String {
        val totalPoints = strokes.sumOf { it.points.size }
        val bounds = strokes.flatMap { it.points }.let { pts ->
            if (pts.isEmpty()) "0x0" else {
                val w = (pts.maxOf { it.x } - pts.minOf { it.x }).toInt()
                val h = (pts.maxOf { it.y } - pts.minOf { it.y }).toInt()
                "${w}x${h}"
            }
        }
        return "${strokes.size} strokes, $totalPoints points, bounds $bounds"
    }
}

enum class RecognitionHint { PROSE, MATH, DIAGRAM }
