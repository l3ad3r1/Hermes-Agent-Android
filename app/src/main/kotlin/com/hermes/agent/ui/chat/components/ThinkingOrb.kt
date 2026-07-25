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
 * What the orb is depicting. Mirrors [com.hermes.agent.domain.agent.AgentPhase],
 * plus [LISTENING], which comes from voice capture rather than the orchestrator.
 */
enum class OrbState {
    THINKING,
    SEARCHING,
    SOLVING,
    WORKING,
    COMPOSING,
    LISTENING,
}

/**
 * Per-state look. Every state draws the same rotating point sphere; only the
 * lattice, dot size, spin rate and scatter change, which is enough to make the
 * six read as distinct at a glance without six separate drawing routines.
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
     * Non-zero turns the sphere into a puzzle: one latitude band at a time
     * spins on its own axis while the rest of the body holds still.
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
)

/** Layer twists per [OrbStyle.twistMs] cycle. */
private const val TWIST_EVENTS = 5

/**
 * Which band each twist grabs, as an offset into the ring count.
 *
 * Deliberately not adjacent and not in order — consecutive bands would read as
 * a wave travelling down the sphere rather than someone working a puzzle.
 */
private val TWIST_ORDER = intArrayOf(4, 1, 7, 2, 5)

/** Proportion of a twist spent turning; the remainder is the pause after it. */
private const val TWIST_DUTY = 0.6f

internal fun styleFor(state: OrbState): OrbStyle = when (state) {
    // Dense bands with few rings: the points crowd into visible lines, which
    // is the banded look the reference uses for thinking.
    OrbState.THINKING -> OrbStyle(
        rings = 7, equatorPoints = 34, dotScale = 0.70f,
        radiusScale = 0.88f, jitter = 0f, spinMs = 3600,
    )
    // A spherical Rubik's cube being worked: a coarse grid of cells, turning
    // slowly as a body while one band at a time twists on its own axis. The
    // ring and cell counts are kept low so the cells stay legible at 32dp —
    // a fine lattice just reads as noise once a layer starts moving.
    OrbState.SEARCHING -> OrbStyle(
        rings = 9, equatorPoints = 16, dotScale = 1.25f,
        radiusScale = 0.88f, jitter = 0f, spinMs = 7000,
        twistMs = 6000, cellTone = 0.62f,
    )
    // Sparser and heavily scattered, against SEARCHING's dense order. The gap
    // has to be this wide: at 32dp a lightly jittered grid is indistinguishable
    // from a clean one, whatever a pixel diff says.
    OrbState.SOLVING -> OrbStyle(
        rings = 9, equatorPoints = 12, dotScale = 1.35f,
        radiusScale = 0.88f, jitter = 0.95f, spinMs = 4200,
    )
    // Sparse, chunky, slow — few large points doing deliberate work.
    OrbState.WORKING -> OrbStyle(
        rings = 7, equatorPoints = 9, dotScale = 1.7f,
        radiusScale = 0.86f, jitter = 0.35f, spinMs = 5200,
    )
    // Weighted toward the equator, turning quickly: output streaming past.
    OrbState.COMPOSING -> OrbStyle(
        rings = 5, equatorPoints = 26, dotScale = 0.85f,
        radiusScale = 0.88f, jitter = 0f, spinMs = 2000,
    )
    // Small and tight, held still-ish — attentive rather than busy.
    OrbState.LISTENING -> OrbStyle(
        rings = 10, equatorPoints = 14, dotScale = 0.9f,
        radiusScale = 0.62f, jitter = 0f, spinMs = 6000,
    )
}

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
    state: OrbState = OrbState.THINKING,
    diameter: Dp = 32.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val context = LocalContext.current
    val animatorScale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    val reducedMotion = animatorScale == 0f
    val style = styleFor(state)

    val transition = rememberInfiniteTransition(label = "thinking-orb")

    // Linear, so the spin never visibly stalls at the loop seam. Keyed on the
    // period so a phase change restarts the animation at the new rate.
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

    // An off-axis angle still reads as a sphere; 0 would line the bands up.
    val rotation = if (reducedMotion) 0.15f else spin
    // Mid-turn, so a still frame shows the puzzle caught in the act.
    val twist = if (reducedMotion) 0.06f else twistCycle

    Canvas(modifier = modifier.size(diameter)) {
        drawOrb(rotation, color, style, twist)
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
 * Extra spin applied to one latitude band, for the puzzle styles.
 *
 * A twist is a **whole** turn, not a quarter. A quarter turn would have to be
 * remembered between frames and accumulated, and whatever it accumulated would
 * snap back to nothing when the cycle wrapped. A full turn leaves the band
 * exactly where it started, so the effect needs no state and the seam at the
 * end of the cycle is invisible.
 */
private fun bandTwist(ring: Int, style: OrbStyle, twist: Float): Float {
    if (style.twistMs <= 0) return 0f
    val t = twist * TWIST_EVENTS
    val event = t.toInt().coerceIn(0, TWIST_EVENTS - 1)
    if (TWIST_ORDER[event] % style.rings != ring) return 0f
    val local = (t - event) / TWIST_DUTY
    if (local >= 1f) return 0f          // holding, back at the start
    val p = local.coerceAtLeast(0f)
    return (p * p * (3f - 2f * p)) * TWO_PI   // ease in and out of the turn
}

/** Visible for rendering tests, which rasterise fixed angles to PNG. */
internal fun DrawScope.drawOrb(
    rotation: Float,
    color: Color,
    style: OrbStyle = styleFor(OrbState.THINKING),
    twist: Float = 0f,
) {
    val radius = size.minDimension / 2f * style.radiusScale
    val mid = center
    val angle = rotation * TWO_PI
    val cosTilt = cos(TILT)
    val sinTilt = sin(TILT)
    val dotBase = radius * 0.055f * style.dotScale

    for (ring in 0 until style.rings) {
        // Half-offset keeps points off the poles, where they would pile up.
        val phiJitter = style.jitter * scatter(ring, 0, 1) * 0.5f
        val phi = PI.toFloat() * (ring + 0.5f + phiJitter) / style.rings
        val bandY = cos(phi)
        val bandRadius = sin(phi)
        val count = max(1, (style.equatorPoints * bandRadius).roundToInt())
        val twistAngle = bandTwist(ring, style, twist)

        for (j in 0 until count) {
            val thetaJitter = style.jitter * scatter(ring, j, 2) * (TWO_PI / count) * 0.5f
            val theta = TWO_PI * j / count + angle + twistAngle + thetaJitter
            val x = bandRadius * cos(theta)
            val z = bandRadius * sin(theta)

            // Tilt about the X axis, then drop the depth term for an
            // orthographic projection.
            val y = bandY * cosTilt - z * sinTilt
            val depth = (bandY * sinTilt + z * cosTilt + 1f) / 2f

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
