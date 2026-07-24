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

/** One full revolution of the sphere, in milliseconds. */
private const val SPIN_PERIOD_MS = 3600

/** Latitude bands the points are laid out on. */
private const val RINGS = 13

/** Points around the widest ring; thinner rings get proportionally fewer. */
private const val EQUATOR_POINTS = 20

/** Fixed tilt (radians) so the sphere is seen slightly from above. */
private const val TILT = 0.42f

/**
 * A rotating sphere of points, shown while the assistant is generating.
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
) {
    val context = LocalContext.current
    val animatorScale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    val reducedMotion = animatorScale == 0f

    val transition = rememberInfiniteTransition(label = "thinking-orb")

    // Linear, so the spin never visibly stalls at the loop seam.
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SPIN_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    // An off-axis angle still reads as a sphere; 0 would line the bands up.
    val rotation = if (reducedMotion) 0.15f else spin

    Canvas(modifier = modifier.size(diameter)) {
        drawOrb(rotation, color)
    }
}

/** Visible for rendering tests, which rasterise fixed angles to PNG. */
internal fun DrawScope.drawOrb(
    rotation: Float,
    color: Color,
) {
    val radius = size.minDimension / 2f * 0.88f
    val mid = center
    val angle = rotation * TWO_PI
    val cosTilt = cos(TILT)
    val sinTilt = sin(TILT)
    val dotBase = radius * 0.055f

    for (ring in 0 until RINGS) {
        // Half-offset keeps points off the poles, where they would pile up.
        val phi = PI.toFloat() * (ring + 0.5f) / RINGS
        val bandY = cos(phi)
        val bandRadius = sin(phi)
        val count = max(1, (EQUATOR_POINTS * bandRadius).roundToInt())

        for (j in 0 until count) {
            val theta = TWO_PI * j / count + angle
            val x = bandRadius * cos(theta)
            val z = bandRadius * sin(theta)

            // Tilt about the X axis, then drop the depth term for an
            // orthographic projection.
            val y = bandY * cosTilt - z * sinTilt
            val depth = (bandY * sinTilt + z * cosTilt + 1f) / 2f

            // Linear, and never faint at the rim. A sharper (e.g. squared)
            // falloff dims the silhouette points, and without a visible
            // outline the whole thing reads as a glowing disc, not a sphere.
            val alpha = 0.30f + 0.70f * depth

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
