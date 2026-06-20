package com.hermes.agent.data.spen

/**
 * A single point in an S Pen stroke, captured at a moment in time.
 *
 * Per Section 5.3 of the plan: "Through the Samsung S Pen SDK, the
 * application receives real-time stroke data including pressure
 * sensitivity, tilt angle, and velocity."
 *
 * All values are normalized where applicable:
 *   - [pressure] is in [0f, 1f] (1f = max pressure).
 *   - [tilt] is in degrees [0f, 90f] (0f = pen perpendicular to screen).
 *   - [x] and [y] are in device-independent pixels relative to the
 *     canvas.
 *   - [timestampMs] is wall-clock millis.
 */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tilt: Float,
    val timestampMs: Long,
)

/**
 * A complete S Pen stroke — the series of points captured between
 * pen-down and pen-up.
 *
 * The orchestrator passes strokes to the [HandwritingRecognitionService]
 * for transcription, or to specialized interpreters (math, diagram)
 * for spatial understanding per Section 5.3.
 */
data class Stroke(
    val id: String,
    val points: List<StrokePoint>,
) {
    /** True if the stroke is empty (no pen-down events captured). */
    val isEmpty: Boolean get() = points.isEmpty()

    /** Bounding box in (left, top, right, bottom) form. */
    val bounds: FloatArray
        get() {
            if (points.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
            var l = Float.POSITIVE_INFINITY
            var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY
            var b = Float.NEGATIVE_INFINITY
            for (p in points) {
                if (p.x < l) l = p.x
                if (p.y < t) t = p.y
                if (p.x > r) r = p.x
                if (p.y > b) b = p.y
            }
            return floatArrayOf(l, t, r, b)
        }
}
