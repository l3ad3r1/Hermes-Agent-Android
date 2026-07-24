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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val TWO_PI = (2.0 * PI).toFloat()

/** Full turn of the internal light, in milliseconds. */
private const val ORBIT_PERIOD_MS = 2600

/** Breathing period of the halo — deliberately not a multiple of the orbit
 *  so the two never resynchronise into an obvious loop. */
private const val BREATH_PERIOD_MS = 1900

/**
 * A soft glowing orb shown while the assistant is generating — the chat's
 * "thinking" indicator, replacing the older three-dot pulse.
 *
 * Pure Compose [Canvas], no images and no WebView: a translucent halo, a core
 * sphere, and two counter-orbiting lobes of light that drift inside the core
 * and give it a slow living shimmer.
 *
 * Colours are pulled from the Material theme, so the orb tracks light/dark and
 * any future palette change for free. It is drawn on top of whatever background
 * the caller provides (in chat, the bubble's `surfaceVariant`).
 *
 * Decorative only — the surrounding bubble in [MessageBubble] already carries
 * the "Assistant is typing" content description for screen readers, so this
 * composable intentionally publishes no semantics of its own.
 *
 * Honours the system "remove animations" setting: when animations are disabled
 * the orb renders a single still frame rather than looping.
 */
@Composable
fun ThinkingOrb(
    modifier: Modifier = Modifier,
    diameter: Dp = 28.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    accent: Color = MaterialTheme.colorScheme.tertiary,
) {
    val context = LocalContext.current
    val animatorScale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    val reducedMotion = animatorScale == 0f

    val transition = rememberInfiniteTransition(label = "thinking-orb")

    // 0..1 ramp driving the orbit; linear so the rotation never visibly stalls.
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(ORBIT_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbit",
    )

    // 0..1 ping-pong driving the halo swell.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(BREATH_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    // A still frame still reads as "busy" — mid-breath, lobes offset — it just
    // does not move.
    val orbitPhase = if (reducedMotion) 0.12f else orbit
    val breathPhase = if (reducedMotion) 0.5f else breath

    Canvas(modifier = modifier.size(diameter)) {
        drawOrb(orbitPhase, breathPhase, color, accent)
    }
}

/** Visible for rendering tests, which rasterise fixed phases to PNG. */
internal fun DrawScope.drawOrb(
    orbit: Float,
    breath: Float,
    color: Color,
    accent: Color,
) {
    val bounds = size.minDimension / 2f
    val mid = center

    // Halo: swells between 88% and 100% of the available radius. Fully
    // transparent at the rim so the orb has no hard edge against the bubble.
    val haloRadius = bounds * (0.88f + 0.12f * breath)
    val haloAlpha = 0.18f + 0.14f * breath
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to color.copy(alpha = haloAlpha),
                0.6f to color.copy(alpha = haloAlpha * 0.4f),
                1.0f to Color.Transparent,
            ),
            center = mid,
            radius = haloRadius,
        ),
        radius = haloRadius,
        center = mid,
    )

    // Core body: deliberately darkened well below the theme colour. The lobes
    // are the bright element, and they only read — especially at 22dp, where
    // fine detail is gone — if the body they travel across is clearly darker.
    val body = lerp(color, Color.Black, 0.20f)
    val coreRadius = bounds * (0.60f + 0.04f * breath)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to body.copy(alpha = 0.96f),
                0.80f to body.copy(alpha = 0.90f),
                1.0f to body.copy(alpha = 0.55f),
            ),
            center = mid,
            radius = coreRadius,
        ),
        radius = coreRadius,
        center = mid,
    )

    // Two lobes of light on opposite sides of the orbit. The vertical squash
    // (0.55) fakes a tilted circular path, so they read as travelling around
    // the inside of a sphere rather than sliding across a flat disc.
    // Clipped to just inside the core, so the lobes can never break the
    // silhouette — without this they drift past the rim and the orb reads as a
    // peanut rather than a sphere with light moving inside it.
    // Deliberately asymmetric: one dominant hot spot plus a smaller, fainter
    // trailing one. Two equal lobes on opposite phases are bilaterally
    // symmetric, which the eye reads as a peanut instead of a rolling sphere.
    val travel = coreRadius * 0.28f
    val lobes = arrayOf(
        Triple(lerp(accent, Color.White, 0.45f), 0.52f, 0.95f),
        Triple(lerp(color, Color.White, 0.35f), 0.36f, 0.50f),
    )
    val coreClip = Path().apply {
        addOval(Rect(center = mid, radius = coreRadius * 0.94f))
    }
    clipPath(coreClip) {
        lobes.forEachIndexed { index, (lobeColor, radiusScale, peakAlpha) ->
            val angle = orbit * TWO_PI + index * PI.toFloat()
            val lobeCenter = Offset(
                x = mid.x + cos(angle) * travel,
                y = mid.y + sin(angle) * travel * 0.55f,
            )
            val lobeRadius = coreRadius * radiusScale
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to lobeColor.copy(alpha = peakAlpha),
                        0.55f to lobeColor.copy(alpha = peakAlpha * 0.42f),
                        1.0f to Color.Transparent,
                    ),
                    center = lobeCenter,
                    radius = lobeRadius,
                ),
                radius = lobeRadius,
                center = lobeCenter,
            )
        }
    }

    // Fixed specular highlight, up and to the left, so the orb keeps a
    // consistent light source while its interior churns. Kept faint — at 0.4
    // it flattened the whole orb into a featureless ball.
    val specularRadius = coreRadius * 0.34f
    val specularCenter = Offset(
        x = mid.x - coreRadius * 0.34f,
        y = mid.y - coreRadius * 0.38f,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to Color.White.copy(alpha = 0.20f),
                1.0f to Color.Transparent,
            ),
            center = specularCenter,
            radius = specularRadius,
        ),
        radius = specularRadius,
        center = specularCenter,
    )
}

@Preview(showBackground = true)
@Composable
private fun ThinkingOrbPreview() {
    ThinkingOrb()
}
