package com.hermes.agent.ui.bloub

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared maths for the bot engine.
 *
 * Faithful Kotlin port of `src/bot/math.ts` from Bloub
 * (github.com/jeremy-prt/bloub, MIT), an SVG study of the x.ai bot avatar whose
 * constants are *measured* off the reference video rather than chosen. Doubles
 * are used throughout the engine — not Floats — so the ported numbers behave
 * exactly as they do in the JavaScript original; the conversion to Float happens
 * once, at the render boundary.
 */

const val TAU: Double = PI * 2

fun clamp(v: Double, lo: Double = 0.0, hi: Double = 1.0): Double = if (v < lo) lo else if (v > hi) hi else v

fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/**
 * Measured on the video: transitions are exponential ease-outs with no body
 * overshoot. The only springy effects are local (the notification pip's pop, the
 * eyes opening) and are written into the state that owns them.
 */
object Easings {
    fun easeOutCubic(t: Double): Double {
        val u = 1 - t
        return 1 - u * u * u
    }

    fun easeInOutCubic(t: Double): Double =
        if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2

    fun easeOutQuint(t: Double): Double {
        val u = 1 - t
        return 1 - u * u * u * u * u
    }
}

/** Periodic 1D noise: loops seamlessly over [period]. Drives the resting gaze drift. */
fun loopNoise(t: Double, period: Double, seed: Double = 0.0): Double {
    val p = (t / period) * TAU
    return 0.55 * sin(p + seed) +
        0.30 * sin(2 * p + seed * 1.7 + 1.1) +
        0.15 * sin(3 * p + seed * 2.3 + 2.4)
}

/**
 * Deterministic PRNG (mulberry32), bit-for-bit identical to the original's
 * `createRng`: Kotlin's wrapping `Int` arithmetic and `ushr` reproduce JS's
 * `>>> 0`/`Math.imul` exactly, so the pre-rolled ring, particle and blink tables
 * come out with the same values.
 */
fun createRng(seed: Int): () -> Double {
    var a = seed
    return {
        a += 0x6d2b79f5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}

internal fun degToRad(d: Double): Double = d * PI / 180

internal fun radToDeg(r: Double): Double = r * 180 / PI

/** Shortest signed angular difference, so +170° → -170° takes the short way round. */
internal fun shortestAngle(delta: Double): Double {
    var d = delta
    while (d > PI) d -= TAU
    while (d < -PI) d += TAU
    return d
}

internal fun hypot(x: Double, y: Double): Double = kotlin.math.sqrt(x * x + y * y)

internal fun cosd(deg: Double): Double = cos(degToRad(deg))

internal fun sind(deg: Double): Double = sin(degToRad(deg))

internal fun absd(v: Double): Double = abs(v)
