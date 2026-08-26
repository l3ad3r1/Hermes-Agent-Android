package com.hermes.agent.ui.bloub

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The eyes are painted on a sphere, not laid flat. Faithful port of
 * `src/bot/face.ts` from Bloub (github.com/jeremy-prt/bloub, MIT).
 *
 * Measured on the video: the eye nearer the edge is 0.69 times the width of the
 * other and its area 0.663 times — exactly the depth factor (z = 0.669) of a
 * point on a sphere at that distance from the centre. So a real head orientation
 * is modelled: each eye takes the sphere's tangent frame, projected
 * orthographically. The compression and the tilt fall out of that on their own,
 * and that is what gives the face its volume.
 *
 * The constants below are not hand-picked: they come from fitting the model to
 * the positions and sizes measured frame by frame (residual error about 1 px on
 * a 190 px radius).
 */

/** Half-separation of the eyes on the sphere, in degrees (total separation about 31 degrees). */
const val EYE_SPLIT: Double = 15.46

/** Resting eye size, in ball-radius units. */
const val EYE_W: Double = 0.186
const val EYE_H: Double = 0.412

/** Head orientation at rest, fitted on the reference frames. */
val REST_GAZE: HeadGaze = HeadGaze(yaw = 28.49, pitch = 28.62, roll = -13.0)

data class HeadGaze(
    /** yaw, degrees, positive = looking right */
    val yaw: Double,
    /** pitch, degrees, positive = looking up */
    val pitch: Double,
    /** roll, degrees, head tilt */
    val roll: Double,
)

class EyePose(
    @JvmField val x: Double,
    @JvmField val y: Double,
    /** 2x2 tangent matrix: [a b c d] in the sense of SVG matrix(a,b,c,d,e,f) */
    @JvmField val a: Double,
    @JvmField val b: Double,
    @JvmField val c: Double,
    @JvmField val d: Double,
    /** z component of the normal: > 0 = front face visible */
    @JvmField val depth: Double,
)

private typealias Vec3 = DoubleArray

/** Rotate two vectors of an orthonormal frame within their common plane. */
private fun spin(u: Vec3, v: Vec3, angle: Double): Pair<Vec3, Vec3> {
    val c = cos(angle)
    val s = sin(angle)
    return doubleArrayOf(
        u[0] * c + v[0] * s,
        u[1] * c + v[1] * s,
        u[2] * c + v[2] * s,
    ) to doubleArrayOf(
        v[0] * c - u[0] * s,
        v[1] * c - u[1] * s,
        v[2] * c - u[2] * s,
    )
}

/**
 * Frame of the head, then of the two eyes.
 *
 * Screen space: x right, y down, z towards the viewer. Index 0 is the inner eye,
 * index 1 the outer one.
 */
fun eyePoses(gaze: HeadGaze, scale: Double, split: Double = EYE_SPLIT): Array<EyePose> {
    var f: Vec3 = doubleArrayOf(0.0, 0.0, 1.0)
    var right: Vec3 = doubleArrayOf(1.0, 0.0, 0.0)
    var down: Vec3 = doubleArrayOf(0.0, 1.0, 0.0)

    // yaw: forward tips towards right
    spin(f, right, degToRad(gaze.yaw)).let { f = it.first; right = it.second }
    // pitch: forward tips upward (so away from down)
    spin(down, f, degToRad(gaze.pitch)).let { down = it.first; f = it.second }
    // roll: the head tilts within its own plane
    spin(right, down, degToRad(gaze.roll)).let { right = it.first; down = it.second }

    fun build(side: Double): EyePose {
        val (ef, er) = spin(f, right, degToRad(split * side))
        return EyePose(
            x = ef[0] * scale,
            y = ef[1] * scale,
            a = er[0],
            b = er[1],
            c = down[0],
            d = down[1],
            depth = ef[2],
        )
    }

    return arrayOf(build(-1.0), build(1.0))
}

/**
 * Life at rest: slow gaze drift, saccades, blinks.
 *
 * A pure function of time (no internal state), so pausing, resuming and jumping
 * to an arbitrary date always give the same image. The values are OFFSETS to add
 * to the current state's pose.
 */
class Liveliness(
    @JvmField val dYaw: Double,
    @JvmField val dPitch: Double,
    @JvmField val dRoll: Double,
    /** 1 = eye open, 0 = closed (vertical squash in screen space) */
    @JvmField val lid: Double,
    @JvmField val driftX: Double,
    @JvmField val driftY: Double,
    @JvmField val breath: Double,
)

/** Pre-rolled blink schedule: deterministic and stateless. */
private val BLINKS: DoubleArray = run {
    val rng = createRng(0x5eed)
    val out = ArrayList<Double>(512)
    var t = 1.4
    while (t < 900) {
        out.add(t)
        // 1.9 to 4.6 s between blinks, plus an occasional double blink
        t += 1.9 + rng() * 2.7
        if (rng() < 0.18) {
            out.add(t)
            t += 0.24
        }
    }
    out.toDoubleArray()
}

/** Measured: 1 to 2 frames at 10 fps. */
private const val BLINK_DUR = 0.18

private fun blinkLid(t: Double): Double {
    for (start in BLINKS) {
        if (t < start) break
        val k = (t - start) / BLINK_DUR
        if (k in 0.0..1.0) {
            // fast close, slightly slower reopen
            return if (k < 0.45) 1 - k / 0.45 else (k - 0.45) / 0.55
        }
    }
    return 1.0
}

fun liveliness(t: Double, wander: Double = 1.0, blink: Boolean = true, float: Boolean = true): Liveliness =
    // Periods coprime with one another: the drift never visibly repeats.
    Liveliness(
        dYaw = (loopNoise(t, 11.3, 0.4) * 5.5 + loopNoise(t, 3.7, 2.1) * 1.6) * wander,
        dPitch = (loopNoise(t, 9.1, 1.3) * 4.2 + loopNoise(t, 4.3, 0.7) * 1.3) * wander,
        dRoll = loopNoise(t, 13.7, 3.2) * 2.2 * wander,
        lid = if (blink) blinkLid(t) else 1.0,
        // At rest the video is almost still (centre stable to +-0.003, radius
        // constant): all the life goes through the gaze and the blinks. Just
        // enough is kept here not to freeze the image completely.
        driftX = if (float) loopNoise(t, 7.9, 1.9) * 0.006 else 0.0,
        driftY = if (float) loopNoise(t, 5.3, 0.3) * 0.007 else 0.0,
        // The width is constant, only the height breathes very slightly.
        breath = if (float) 1 + sin((t / 3.4) * PI * 2) * 0.005 else 1.0,
    )

/**
 * The blink is a VERTICAL squash in screen space about the eye's centre
 * (measured: the bounding-box width is preserved, the height falls to about
 * 0.35), not a shrink along the capsule's tilted axis. So it is composed after
 * the tangent matrix, affecting only the y outputs.
 */
fun blinkScale(lid: Double): Double = 0.06 + 0.94 * clamp(lid)
