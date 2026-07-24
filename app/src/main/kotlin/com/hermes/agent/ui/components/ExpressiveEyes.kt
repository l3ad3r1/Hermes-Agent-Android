package com.hermes.agent.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import android.provider.Settings
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.agent.ui.home.HermesPersona.Mood
import kotlin.random.Random

/**
 * Hermes's face: a pair of expressive robot eyes, inspired by the Xiaozhi
 * ESP32-S3 desk robot (github.com/TechTalkies/Xiaozhi-for-XiaoESP32S3),
 * whose whole personality is carried by two animated eyes on a tiny display.
 *
 * Behaviors, all pure Compose Canvas (no images):
 *  - **Blink**: double-eyelid blink at randomized 2.5–6s intervals.
 *  - **Gaze**: occasional saccade — the eyes glance a random direction,
 *    dwell, and return to center.
 *  - **Moods** (from [HermesPersona.Mood]):
 *      HAPPY   → upward crescent squint (the classic ^ ^ robot smile-eyes)
 *      NEUTRAL → tall rounded-square eyes, full blink/gaze life
 *      FOCUSED → half-lidded, gaze pinned slightly down (busy working)
 *      SLEEPY  → nearly closed lids with a slow droop cycle
 */
@Composable
fun ExpressiveEyes(
    mood: Mood,
    modifier: Modifier = Modifier,
    eyeColor: Color,
    width: Dp = 84.dp,
    height: Dp = 44.dp,
) {
    val context = LocalContext.current
    val animatorScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val reducedMotion = animatorScale == 0f

    // Lid openness driven by mood + blink overlay.
    val moodOpenTarget = when (mood) {
        Mood.HAPPY -> 1f      // crescent shape handles the squint
        Mood.NEUTRAL -> 1f
        Mood.FOCUSED -> 0.55f
        Mood.SLEEPY -> 0.28f
        Mood.THINKING -> 0.82f
        Mood.SURPRISED -> 1f
        Mood.LISTENING -> 1f
        Mood.CELEBRATE -> 1f  // crescent handles the shape
    }
    val moodOpen by animateFloatAsState(moodOpenTarget, tween(450), label = "moodOpen")

    // LISTENING: a gentle attentive scale pulse (like a soft breathing focus).
    val listenPulse = remember { Animatable(1f) }
    LaunchedEffect(mood) {
        if (reducedMotion) return@LaunchedEffect
        if (mood == Mood.LISTENING) {
            while (true) {
                listenPulse.animateTo(1.12f, tween(620))
                listenPulse.animateTo(1f, tween(620))
            }
        } else {
            listenPulse.snapTo(1f)
        }
    }

    // CELEBRATE: a couple of vertical hops.
    val bounce = remember { Animatable(0f) }
    LaunchedEffect(mood) {
        if (reducedMotion) return@LaunchedEffect
        if (mood == Mood.CELEBRATE) {
            bounce.snapTo(0f)
            repeat(2) {
                bounce.animateTo(-1f, tween(150))
                bounce.animateTo(0f, tween(220))
            }
        } else {
            bounce.snapTo(0f)
        }
    }

    // Startle pop: SURPRISED eyes overshoot in scale, then settle.
    val startle = remember { Animatable(1f) }
    LaunchedEffect(mood) {
        if (reducedMotion) return@LaunchedEffect
        if (mood == Mood.SURPRISED) {
            startle.snapTo(0.7f)
            startle.animateTo(1.22f, tween(120))
            startle.animateTo(1f, tween(180))
        } else {
            startle.animateTo(1f, tween(150))
        }
    }

    val blink = remember { Animatable(1f) }
    LaunchedEffect(mood) {
        if (reducedMotion) return@LaunchedEffect
        if (mood == Mood.SURPRISED) {
            blink.snapTo(1f) // wide awake — no blinking mid-startle
            return@LaunchedEffect
        }
        while (true) {
            // Sleepy eyes blink slower and stay half-shut longer.
            val interval = if (mood == Mood.SLEEPY) Random.nextLong(3200, 7000)
            else Random.nextLong(2500, 6000)
            kotlinx.coroutines.delay(interval)
            blink.animateTo(0f, tween(if (mood == Mood.SLEEPY) 220 else 90))
            blink.animateTo(1f, tween(if (mood == Mood.SLEEPY) 320 else 110))
            // Occasional double blink, robots do this and it's charming.
            if (mood != Mood.SLEEPY && Random.nextFloat() < 0.25f) {
                blink.animateTo(0f, tween(80))
                blink.animateTo(1f, tween(100))
            }
        }
    }

    // Gaze saccades. FOCUSED pins the gaze slightly down-left ("reading");
    // THINKING looks up and drifts side to side ("recalling").
    val gazeX = remember { Animatable(0f) }
    val gazeY = remember { Animatable(0f) }
    LaunchedEffect(mood) {
        if (reducedMotion) return@LaunchedEffect
        when (mood) {
            Mood.FOCUSED -> {
                gazeX.animateTo(-0.35f, tween(300))
                gazeY.animateTo(0.4f, tween(300))
                while (true) {
                    // Small scanning movements while working.
                    kotlinx.coroutines.delay(Random.nextLong(900, 2100))
                    gazeX.animateTo(Random.nextFloat() * 0.9f - 0.55f, tween(220))
                }
            }
            Mood.THINKING -> {
                gazeY.animateTo(-0.55f, tween(280))
                gazeX.animateTo(0.45f, tween(280))
                while (true) {
                    // Slow pendulum between up-right and up-left.
                    kotlinx.coroutines.delay(Random.nextLong(1100, 2000))
                    gazeX.animateTo(-gazeX.value.coerceIn(-0.45f, 0.45f), tween(420))
                }
            }
            Mood.SURPRISED, Mood.CELEBRATE -> {
                gazeX.animateTo(0f, tween(100))
                gazeY.animateTo(0f, tween(100))
            }
            Mood.LISTENING -> {
                // Attentive: eyes up a touch, small alert flicks.
                gazeY.animateTo(-0.2f, tween(200))
                gazeX.animateTo(0f, tween(200))
                while (true) {
                    kotlinx.coroutines.delay(Random.nextLong(700, 1500))
                    gazeX.animateTo(Random.nextFloat() * 0.5f - 0.25f, tween(180))
                }
            }
            else -> {
                gazeX.snapTo(0f); gazeY.snapTo(0f)
                while (true) {
                    kotlinx.coroutines.delay(Random.nextLong(1800, 5200))
                    // Glance somewhere…
                    gazeX.animateTo(Random.nextFloat() * 1.6f - 0.8f, tween(260))
                    gazeY.animateTo(Random.nextFloat() * 0.8f - 0.4f, tween(260))
                    kotlinx.coroutines.delay(Random.nextLong(500, 1400))
                    // …and settle back to center.
                    gazeX.animateTo(0f, tween(300))
                    gazeY.animateTo(0f, tween(300))
                }
            }
        }
    }

    Canvas(modifier = modifier.size(width, height)) {
        val open = (moodOpen * blink.value).coerceIn(0.06f, 1f)
        val scale = startle.value * listenPulse.value
        val eyeW = size.width * 0.34f * scale
        val gap = size.width * 0.32f
        val maxH = size.height * 0.92f * scale
        val cx1 = (size.width - gap) / 2f - eyeW / 2f
        val cx2 = (size.width + gap) / 2f + eyeW / 2f
        val cy = size.height / 2f + gazeY.value * size.height * 0.12f + bounce.value * size.height * 0.2f
        val dx = gazeX.value * eyeW * 0.22f

        if (mood == Mood.SURPRISED) {
            // Startled: perfectly round wide eyes.
            val r = minOf(eyeW, maxH) * 0.52f
            listOf(cx1, cx2).forEach { cx ->
                drawCircle(color = eyeColor, radius = r, center = Offset(cx + dx, cy))
            }
        } else if ((mood == Mood.HAPPY || mood == Mood.CELEBRATE) && blink.value > 0.5f) {
            // Crescent smile-eyes: thick upward arcs.
            val stroke = Stroke(width = maxH * 0.22f, cap = StrokeCap.Round)
            listOf(cx1, cx2).forEach { cx ->
                val r = eyeW * 0.52f
                val rect = Rect(
                    left = cx - r + dx, top = cy - r * 0.7f,
                    right = cx + r + dx, bottom = cy + r * 1.1f,
                )
                val path = Path().apply { arcTo(rect, 180f, 180f, forceMoveTo = true) }
                drawPath(path, eyeColor, style = stroke)
            }
        } else {
            // Rounded-square eyes whose height is the lid openness.
            val h = maxH * open
            listOf(cx1, cx2).forEach { cx ->
                drawRoundedEye(
                    center = Offset(cx + dx, cy),
                    w = eyeW,
                    h = h,
                    color = eyeColor,
                )
            }
        }
    }
}

private fun DrawScope.drawRoundedEye(center: Offset, w: Float, h: Float, color: Color) {
    val radius = minOf(w, h) * 0.45f
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - w / 2f, center.y - h / 2f),
        size = Size(w, h),
        cornerRadius = CornerRadius(radius, radius),
    )
}
