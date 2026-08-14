package com.hermes.agent.ui.chat.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TWO_PI = (2.0 * PI).toFloat()

/** Fixed tilt (radians) so the sphere is seen slightly from above. */
private const val TILT = 0.42f

/**
 * The orb's look.
 *
 * One style, used everywhere. There were six — one per agent phase — but they
 * are gone: the phases they marked are mostly sub-second, so in practice the
 * chat bubble flickered between lattices nobody could read, and the puzzle was
 * the only one worth watching. The knobs are kept because they are what the
 * drawing code is written against, not because anything varies them.
 */
internal data class OrbStyle(
    /** Latitude bands. Few + many points per band reads as stripes. */
    val rings: Int,
    /** Points around the widest band; thinner bands get proportionally fewer. */
    val equatorPoints: Int,
    /** Multiplier on the base dot radius. */
    val dotScale: Float,
    /** Sphere size within the canvas. */
    val radiusScale: Float,
    /** Lattice scatter, 0 = perfect grid. Deterministic per point, not per frame. */
    val jitter: Float,
    /** Milliseconds per revolution. */
    val spinMs: Int,
    /**
     * Milliseconds for one full round of layer twists, or 0 for none.
     *
     * Non-zero turns the sphere into a puzzle: one layer at a time turns while
     * the rest of the body holds still, alternating between horizontal bands
     * and vertical slices.
     */
    val twistMs: Int = 0,
    /**
     * Per-cell brightness variation, 0 for a uniform lattice.
     *
     * Required for [twistMs] to be worth anything. A band of identical, evenly
     * spaced dots is rotationally symmetric — turn it and it looks untouched.
     * Giving each cell a fixed tone is what a Rubik's cube's stickers do: it
     * makes the rotation legible.
     */
    val cellTone: Float = 0f,
    /**
     * Milliseconds for one breath — the sphere swelling and settling — or 0 for
     * a body that holds its size.
     */
    val breathMs: Int = 0,
    /** How much of the radius the breath takes, as a fraction. */
    val breathDepth: Float = 0f,
)

/** Layer twists per [OrbStyle.twistMs] cycle. */
private const val TWIST_EVENTS = 6

/** A horizontal band — a ring of constant latitude, turning about the poles. */
private const val AXIS_BAND = 0

/** A vertical slice — a slab of constant x, turning end over end. */
private const val AXIS_SLICE = 1

/**
 * Which axis each twist turns about.
 *
 * Alternating matters: turning only bands moves every cell along the same
 * horizontal path, and the sphere reads as a body being stirred rather than a
 * puzzle being worked. Vertical slices give the motion its second direction.
 */
private val TWIST_AXES = intArrayOf(AXIS_BAND, AXIS_SLICE, AXIS_BAND, AXIS_SLICE, AXIS_BAND, AXIS_SLICE)

/**
 * Which layer each twist grabs, as an offset into the layer count.
 *
 * Deliberately not adjacent and not in order — consecutive layers would read as
 * a wave travelling across the sphere rather than someone working a puzzle.
 *
 * Slices stay near the middle of their range. A slice is a slab of constant x,
 * so an outer one cuts the sphere near its edge, where the cross-section is a
 * small circle of a few dim cells and the turn is invisible; a central one cuts
 * a great circle straight through the face.
 */
private val TWIST_ORDER = intArrayOf(4, 4, 6, 3, 2, 5)

/** Proportion of a twist spent turning; the remainder is the pause after it. */
private const val TWIST_DUTY = 0.6f

/**
 * Slice thickness, in layers.
 *
 * Wider than a band is deep, because the two are not symmetric: a band already
 * owns a whole ring of cells, while a one-layer slab catches only the handful
 * that happen to sit near that plane — and half of those are on the far side,
 * drawn dim. Below about 1.5 the slice turn is too sparse to read.
 */
private const val SLICE_LAYERS = 1.6f

/**
 * A spherical Rubik's cube being worked: a coarse grid of cells turning slowly
 * as a body, while one layer at a time twists on its own axis.
 *
 * Ring and cell counts are deliberately low. At 32dp a finer lattice just reads
 * as noise once a layer starts moving, and the cells have to stay separable for
 * the twist to be visible at all.
 */
internal val PUZZLE = OrbStyle(
    rings = 9,
    equatorPoints = 16,
    dotScale = 1.25f,
    radiusScale = 0.88f,
    jitter = 0f,
    spinMs = 7000,
    twistMs = 6000,
    cellTone = 0.62f,
)

/**
 * Listening: the sphere turns slowly and breathes, and nothing twists.
 *
 * Deliberately the opposite of [PUZZLE]. Working is busy and fidgety — layers
 * snapping round, cells in three tones. Waiting for someone to speak should
 * read as calm and attentive, so the layer twists are gone, the tones are
 * flattened towards even, and the whole body swells and settles instead.
 *
 * Sized a little smaller at rest so the swell has somewhere to go without the
 * orb overflowing the button it sits in.
 */
internal val BREATHING = OrbStyle(
    rings = 10,
    equatorPoints = 18,
    dotScale = 1.0f,
    radiusScale = 0.80f,
    jitter = 0f,
    spinMs = 11000,
    twistMs = 0,
    cellTone = 0.30f,
    breathMs = 2600,
    breathDepth = 0.18f,
)

/**
 * A rotating sphere of points, shown while the assistant is busy.
 *
 * Points sit on latitude bands of a unit sphere, spin about the vertical axis,
 * and are projected orthographically. Depth drives both alpha and dot size, so
 * the far side reads as behind the near side and the silhouette naturally
 * densifies at the rim — that is what makes a flat scatter of dots read as a
 * solid rotating body.
 *
 * Modelled on the Thinking Orbs indicators (orbs.jakubantalik.com), which are a
 * React canvas package and so unusable here. Pure Compose [Canvas], no images
 * and no WebView, following the same approach as ExpressiveEyes.
 *
 * A points-on-black treatment happens to suit Hermes exactly: the app's palette
 * is deliberately monochrome, so the single theme colour is all this needs.
 *
 * Decorative only — the surrounding bubble in [MessageBubble] already carries
 * the "Assistant is typing" content description for screen readers, so this
 * composable intentionally publishes no semantics of its own.
 *
 * Honours the system "remove animations" setting: when animations are disabled
 * the sphere renders at a fixed angle rather than spinning.
 */
@Composable
fun ThinkingOrb(
    modifier: Modifier = Modifier,
    diameter: Dp = 32.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    /** Breathe instead of working the puzzle — Hermes is waiting to be spoken to. */
    listening: Boolean = false,
) {
    val context = LocalContext.current
    val animatorScale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    val reducedMotion = animatorScale == 0f
    val style = if (listening) BREATHING else PUZZLE

    val transition = rememberInfiniteTransition(label = "thinking-orb")

    // Linear, so the spin never visibly stalls at the loop seam.
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(style.spinMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    // Its own clock, deliberately not a divisor of spinMs, so the twists do not
    // land on the same point of the body's rotation every cycle.
    val twistCycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(style.twistMs.coerceAtLeast(1), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "twist",
    )

    // Reverse, not Restart: a breath has to settle back the way it came. A
    // sawtooth would snap from full to empty at the loop seam.
    val breathCycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(style.breathMs.coerceAtLeast(1), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    // An off-axis angle still reads as a sphere; 0 would line the bands up.
    val rotation = if (reducedMotion) 0.15f else spin
    // Mid-turn, so a still frame shows the puzzle caught in the act.
    val twist = if (reducedMotion) 0.06f else twistCycle
    // Half-inflated when animations are off, so the still orb is not the
    // smallest it ever gets.
    val breath = if (reducedMotion) 0.5f else breathCycle

    Canvas(modifier = modifier.size(diameter)) {
        drawOrb(rotation, color, style, twist, breath)
    }
}

/**
 * Deterministic scatter in -1..1 for a given lattice position.
 *
 * Has to be stable across frames: a per-frame random would make every point
 * twitch independently and destroy the sense of a rigid rotating body.
 */
private fun scatter(ring: Int, index: Int, salt: Int): Float {
    var h = ring * 73856093 xor index * 19349663 xor salt * 83492791
    h = h xor (h shl 13)
    h = h xor (h ushr 17)
    h = h xor (h shl 5)
    return (h and 0xFFFF) / 32768f - 1f
}

/**
 * The twist in progress: which axis, which layer, and how far through the turn.
 *
 * A twist is a **whole** turn, not a quarter. A quarter turn would have to be
 * remembered between frames and accumulated, and whatever it accumulated would
 * snap back to nothing when the cycle wrapped. A full turn leaves the layer
 * exactly where it started, so the effect needs no state and the seam at the
 * end of the cycle is invisible.
 */
private class Twist(val axis: Int, val layer: Int, val angle: Float)

private fun twistAt(style: OrbStyle, twist: Float): Twist? {
    if (style.twistMs <= 0) return null
    val t = twist * TWIST_EVENTS
    val event = t.toInt().coerceIn(0, TWIST_EVENTS - 1)
    val local = (t - event) / TWIST_DUTY
    if (local >= 1f) return null        // holding, back where it started
    val p = local.coerceAtLeast(0f)
    val eased = p * p * (3f - 2f * p)   // ease in and out of the turn
    return Twist(
        axis = TWIST_AXES[event],
        layer = TWIST_ORDER[event] % style.rings,
        angle = eased * TWO_PI,
    )
}

/** Visible for rendering tests, which rasterise fixed angles to PNG. */
internal fun DrawScope.drawOrb(
    rotation: Float,
    color: Color,
    style: OrbStyle = PUZZLE,
    twist: Float = 0f,
    /** Breath phase, 0 at rest and 1 fully swelled. Ignored unless the style breathes. */
    breath: Float = 0f,
) {
    // Smoothed rather than linear: a breath eases at the top and bottom of its
    // travel, and a raw ramp reads as a mechanical throb.
    val eased = breath * breath * (3f - 2f * breath)
    val swell = 1f + style.breathDepth * eased
    val radius = size.minDimension / 2f * style.radiusScale * swell
    val mid = center
    val angle = rotation * TWO_PI
    val cosSpin = cos(angle)
    val sinSpin = sin(angle)
    val cosTilt = cos(TILT)
    val sinTilt = sin(TILT)
    val dotBase = radius * 0.055f * style.dotScale
    val active = twistAt(style, twist)
    val cosTwist = active?.let { cos(it.angle) } ?: 1f
    val sinTwist = active?.let { sin(it.angle) } ?: 0f

    for (ring in 0 until style.rings) {
        // Half-offset keeps points off the poles, where they would pile up.
        val phiJitter = style.jitter * scatter(ring, 0, 1) * 0.5f
        val phi = PI.toFloat() * (ring + 0.5f + phiJitter) / style.rings
        val bandY = cos(phi)
        val bandRadius = sin(phi)
        val count = max(1, (style.equatorPoints * bandRadius).roundToInt())

        for (j in 0 until count) {
            // Body frame first, before the sphere's own rotation. Layers have to
            // be picked here: the body spin moves points continuously, so a
            // layer chosen in view coordinates would gain and shed cells
            // part-way through its turn and tear itself apart.
            val thetaJitter = style.jitter * scatter(ring, j, 2) * (TWO_PI / count) * 0.5f
            val theta0 = TWO_PI * j / count + thetaJitter
            var bx = bandRadius * cos(theta0)
            var by = bandY
            var bz = bandRadius * sin(theta0)

            if (active != null) {
                val inLayer = when (active.axis) {
                    // A ring of constant latitude.
                    AXIS_BAND -> ring == active.layer
                    // A slab of constant x, centred on the chosen layer.
                    else -> {
                        val step = 2f / style.rings
                        val mid = -1f + (active.layer + 0.5f) * step
                        val half = step * SLICE_LAYERS / 2f
                        bx >= mid - half && bx < mid + half
                    }
                }
                if (inLayer) {
                    if (active.axis == AXIS_BAND) {
                        val nx = bx * cosTwist - bz * sinTwist
                        bz = bx * sinTwist + bz * cosTwist
                        bx = nx
                    } else {
                        val ny = by * cosTwist - bz * sinTwist
                        bz = by * sinTwist + bz * cosTwist
                        by = ny
                    }
                }
            }

            // The body's own rotation, about the vertical axis.
            val x = bx * cosSpin - bz * sinSpin
            val sz = bx * sinSpin + bz * cosSpin

            // Tilt about the X axis, then drop the depth term for an
            // orthographic projection.
            val y = by * cosTilt - sz * sinTilt
            val depth = (by * sinTilt + sz * cosTilt + 1f) / 2f

            // Linear, and never faint at the rim. A sharper (e.g. squared)
            // falloff dims the silhouette points, and without a visible
            // outline the whole thing reads as a glowing disc, not a sphere.
            val depthAlpha = 0.30f + 0.70f * depth

            // Three discrete tones rather than a smooth ramp — stickers, not a
            // gradient. Keyed on the cell's own index so the tone travels with
            // the cell when its band twists.
            val tone = if (style.cellTone > 0f) {
                val level = (((scatter(ring, j, 7) + 1f) / 2f) * 3f).toInt().coerceIn(0, 2)
                1f - style.cellTone * (level / 2f)
            } else {
                1f
            }
            val alpha = depthAlpha * tone

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = dotBase * (0.45f + 0.55f * depth),
                center = Offset(
                    x = mid.x + x * radius,
                    y = mid.y + y * radius,
                ),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ThinkingOrbPreview() {
    ThinkingOrb()
}
