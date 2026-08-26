package com.hermes.agent.ui.bloub

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything drawn around the body: the orbit rings, the comet ribbons, the
 * thinking dots, the burst particles, the notification pip. Faithful port of
 * `src/bot/decor.ts` from Bloub (github.com/jeremy-prt/bloub, MIT).
 */

/**
 * The rings are not flat colours: the video shows a full hue wheel at constant
 * lightness, with a gradient along each stroke. Measured: S 45-62 %, L 50-67 %.
 */
internal fun wheel(hue: Double, s: Double = 0.55, l: Double = 0.62): Int {
    val h = ((hue % 360) + 360) % 360
    val c = (1 - abs(2 * l - 1)) * s
    val x = c * (1 - abs((h / 60) % 2 - 1))
    val m = l - c / 2
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0.0)
        h < 120 -> Triple(x, c, 0.0)
        h < 180 -> Triple(0.0, c, x)
        h < 240 -> Triple(0.0, x, c)
        h < 300 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    fun ch(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

class DotRender(
    @JvmField val x: Double,
    @JvmField val y: Double,
    @JvmField val r: Double,
    @JvmField val opacity: Double,
    /** explicit colour; by default the renderer uses the body's */
    @JvmField val color: Int? = null,
    /**
     * Depth haze: 0 = melted into the background, 1 = full body colour. The mix
     * happens at render time, which alone knows the chosen colour.
     */
    @JvmField val depth: Double? = null,
    /**
     * A non-circular shape, in ball-radius units and centred on the origin (the
     * tilted exclamation mark's dot is a teardrop, not a disc). When it is given,
     * [r] is no longer used for the outline.
     */
    @JvmField val poly: List<Pt>? = null,
    /** rotation applied to [poly], in degrees */
    @JvmField val rot: Double = 0.0,
) {
    fun scaled(offX: Double, offY: Double, scale: Double) = DotRender(
        x = (x + offX) * scale,
        y = (y + offY) * scale,
        r = r * scale,
        opacity = opacity,
        color = color,
        depth = depth,
        poly = poly,
        rot = rot,
    )

    fun faded(k: Double) = DotRender(x, y, r, opacity * k, color, depth, poly, rot)
}

/**
 * What a state declares: the arc's geometry stays in ball-radius units, and the
 * engine — the only thing that knows the viewBox scale — rasterises it.
 */
class ArcSpec(
    @JvmField val id: String,
    @JvmField val seed: ArcSeed,
    @JvmField val t: Double,
    @JvmField val opacity: Double,
) {
    fun faded(k: Double, prefix: String) = ArcSpec("$prefix$id", seed, t, opacity * k)
}

/** A gradient along the stroke: two endpoints and three stops. */
class ArcGradient(
    @JvmField val x1: Double,
    @JvmField val y1: Double,
    @JvmField val x2: Double,
    @JvmField val y2: Double,
    @JvmField val stops: IntArray,
)

class ArcRender(
    @JvmField val id: String,
    /** the part in front of the body, as polylines of flat (x, y) pairs */
    @JvmField val front: List<DoubleArray>,
    /** the part behind the body, drawn first so the silhouette occludes it */
    @JvmField val back: List<DoubleArray>,
    @JvmField val width: Double,
    @JvmField val opacity: Double,
    @JvmField val grad: ArcGradient,
)

class ArcSeed(
    /** semi-major axis, in ball-radius units */
    @JvmField val a: Double,
    /** flattening b/a: measured <= 0.45, the orbit planes are seen nearly edge-on */
    @JvmField val k: Double,
    /** tilt of the major axis on screen, radians */
    @JvmField val tilt: Double,
    /** turns per second */
    @JvmField val speed: Double,
    @JvmField val phase: Double,
    /** fraction of the turn actually drawn */
    @JvmField val sweep: Double,
    @JvmField val hue: Double,
    @JvmField val hueSpan: Double,
    @JvmField val width: Double,
    @JvmField val cx: Double,
    @JvmField val cy: Double,
)

/**
 * Project a tilted 3D circle orthographically.
 *
 * The circle lives in the plane spanned by u (in the screen) and v (which dives
 * into depth). The z component splits the arc in two: the back half is drawn
 * before the body and so is occluded by it. It is this genuine depth sort that
 * makes the rings read as orbits and not as a flat drawing.
 */
fun arcRender(seed: ArcSeed, t: Double, scale: Double, id: String, opacity: Double = 1.0): ArcRender {
    val spin = seed.phase + t * seed.speed * TAU
    val cu = cos(seed.tilt)
    val su = sin(seed.tilt)
    val kz = sqrt(kotlin.math.max(0.0, 1 - seed.k * seed.k))

    val n = 64
    val span = seed.sweep * TAU
    // One run per contiguous stretch on the same side of the body: the original
    // emits an SVG `M` whenever the depth sign flips, which is the same break.
    val frontRuns = ArrayList<ArrayList<Double>>(2)
    val backRuns = ArrayList<ArrayList<Double>>(2)
    var prev: Boolean? = null

    for (i in 0..n) {
        val th = spin + (i.toDouble() / n) * span
        val ct = cos(th)
        val st = sin(th)
        // u = (cos tilt, sin tilt, 0) ; v = (-sin tilt * k, cos tilt * k, kz)
        val x = seed.a * (ct * cu + st * -su * seed.k) + seed.cx
        val y = seed.a * (ct * su + st * cu * seed.k) + seed.cy
        val z = seed.a * st * kz

        val behind = z < 0
        val runs = if (behind) backRuns else frontRuns
        if (behind != prev) runs.add(ArrayList(n * 2))
        val run = runs[runs.size - 1]
        run.add(x * scale)
        run.add(y * scale)
        prev = behind
    }

    // A run of a single point draws nothing: drop it rather than emit an empty path.
    fun flatten(runs: List<ArrayList<Double>>): List<DoubleArray> =
        runs.filter { it.size >= 4 }.map { r -> DoubleArray(r.size) { r[it] } }

    val front = flatten(frontRuns)
    val back = flatten(backRuns)

    val gx = cos(seed.tilt) * seed.a * scale
    val gy = sin(seed.tilt) * seed.a * scale
    return ArcRender(
        id = id,
        front = front,
        back = back,
        width = seed.width * scale,
        opacity = opacity,
        grad = ArcGradient(
            x1 = seed.cx * scale - gx,
            y1 = seed.cy * scale - gy,
            x2 = seed.cx * scale + gx,
            y2 = seed.cy * scale + gy,
            stops = intArrayOf(
                wheel(seed.hue),
                wheel(seed.hue + seed.hueSpan * 0.5),
                wheel(seed.hue + seed.hueSpan),
            ),
        ),
    )
}

// ─────────────────────────────────────────────────────────────────── rings

private val RING_RNG = createRng(0xa11ce)

/**
 * 6 rings, semi-major axis 1.30-1.40 (so clearly larger than the ball),
 * flattening always <= 0.45, thickness 0.055, about 3.3 turns/s.
 */
val RINGS: List<ArcSeed> = List(6) { i ->
    ArcSeed(
        a = 1.3 + RING_RNG() * 0.1,
        k = 0.05 + RING_RNG() * 0.4,
        tilt = (i.toDouble() / 6) * PI + RING_RNG() * 0.5,
        speed = 3 + RING_RNG() * 0.7,
        phase = RING_RNG() * TAU,
        sweep = 0.6 + RING_RNG() * 0.25,
        hue = (i * 360.0) / 6 + RING_RNG() * 30,
        hueSpan = 60 + RING_RNG() * 60,
        width = 0.05 + RING_RNG() * 0.012,
        cx = 0.0,
        cy = 0.1,
    )
}

/**
 * The nested sheaf of arcs that sweeps across the triangle just before the
 * orbits. Seen almost edge-on (hence the hairpin shape), rmax 1.37.
 */
val SWOOSH: List<ArcSeed> = List(4) { i ->
    ArcSeed(
        a = 0.78 + i * 0.2,
        k = 0.05 + i * 0.02,
        tilt = -0.62 + i * 0.05,
        speed = 0.3,
        phase = 0.06 * i,
        sweep = 0.4,
        hue = 95.0 + i * 62,
        hueSpan = 100.0,
        width = 0.05,
        cx = 0.0,
        cy = -0.12,
    )
}

// ───────────────────────────────────────────────────────────── three dots

/** Measured x: -0.557 / -0.013 / +0.532, y = 0. */
val DOT_X: DoubleArray = doubleArrayOf(-0.557, -0.013, 0.532)
const val DOT_R: Double = 0.165
const val DOT_PEAK: Double = 1.25

// ────────────────────────────────────────────────────────────── particles

private val P_RNG = createRng(0xbeef)

/** 5 particles, a new one every 0.2 s, lifetime 0.55 s. */
private val PARTICLES: List<Triple<Double, Double, Double>> = List(5) { i ->
    Triple(i * 0.2, P_RNG() * TAU, 0.58 + P_RNG() * 0.18)
}

/**
 * The particles do not fly off in a straight line: they spiral inwards (radius
 * x0.75 per frame, angle +100 deg/s) while growing, and pass behind the core
 * where they are swallowed.
 */
fun particles(t: Double, scale: Double): List<DotRender> {
    val out = ArrayList<DotRender>(PARTICLES.size)
    for ((birth, angle, rho0) in PARTICLES) {
        val u = t - birth
        if (u < 0 || u > 0.62) continue
        val rho = rho0 * 0.75.pow(u * 10)
        val a = angle + degToRad(u * 100)
        out.add(
            DotRender(
                x = cos(a) * rho * scale,
                y = sin(a) * rho * scale,
                r = (0.04 + 0.028 * clamp(u / 0.55)) * scale,
                opacity = clamp(u / 0.06) * clamp((0.62 - u) / 0.08),
                depth = clamp(1 - rho / 0.8),
            )
        )
    }
    return out
}

// ───────────────────────────────────────────────────────────────── comet

/**
 * Counter-intuitively, the dot does not cross the screen: it stays at the centre
 * and it is the trail that orbits it. Ellipse a = 0.85, b = 0.15, major axis
 * tilted +34 degrees, 4 ribbons, about 210 deg/s.
 */
private val COMET_RNG = createRng(0xc0e7)

val COMET_RIBBONS: List<ArcSeed> = List(4) { i ->
    val d = i - 1.5
    ArcSeed(
        a = 0.85 * (1 + d * 0.03),
        // the same flattening to within +-5 %: the ribbons form a tight sheaf
        k = (0.15 / 0.85) * (1 + d * 0.16),
        tilt = degToRad(34.0) + d * 0.035,
        speed = 210.0 / 360,
        // measured phase offset: 10 to 20 degrees between ribbons, no more
        phase = -i * 0.045 + COMET_RNG() * 0.012,
        sweep = 0.34,
        hue = i * 85.0 + COMET_RNG() * 20,
        hueSpan = 80.0,
        width = 0.095,
        cx = 0.0,
        cy = 0.0,
    )
}

/** Radius of the comet's dot, measured at 0.129. */
const val COMET_DOT: Double = 0.129

// ──────────────────────────────────────────────────── notification pip

/** Blue read off the pixels. */
const val NOTIF_BLUE: Int = 0xFF2496E8.toInt()

/** The pip sits exactly on the circumference, at -42 degrees. */
const val NOTIF_ANGLE: Double = -42.0
const val NOTIF_DIST: Double = 1.003

/** Resting radius; the pop peaks 14 % above it. */
const val NOTIF_R: Double = 0.15
const val NOTIF_POP: Double = 1.14

/**
 * The notch is a disc concentric with the pip, subtracted from the body. The
 * margin is constant (0.054 R) and follows the body's scale.
 */
const val NOTIF_MARGIN: Double = 0.054
