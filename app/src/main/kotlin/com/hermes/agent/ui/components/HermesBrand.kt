package com.hermes.agent.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Hermes brand mark — a rotated rounded square ("diamond") sitting on an
 * accent tile, matching the Nous design. Used in onboarding, chat header and
 * the home dashboard.
 */
@Composable
fun HermesDiamond(
    modifier: Modifier = Modifier,
    tileSize: Dp = 62.dp,
    glyphSize: Dp = 22.dp,
    cornerRadius: Dp = 18.dp,
    tileColor: Color = MaterialTheme.colorScheme.primary,
    glyphColor: Color = MaterialTheme.colorScheme.onPrimary,
    glow: Boolean = true,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .then(
                if (glow) Modifier.shadow(
                    elevation = 28.dp,
                    shape = shape,
                    ambientColor = tileColor,
                    spotColor = tileColor,
                ) else Modifier
            )
            .size(tileSize)
            .background(tileColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(glyphSize)
                .rotate(45f)
                .background(glyphColor, RoundedCornerShape(glyphSize / 7)),
        )
    }
}

/**
 * A monospace block-cursor that blinks, for terminal-style chips
 * (`$ hermes connect▌`).
 */
@Composable
fun BlinkingCursor(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    width: Dp = 8.dp,
    height: Dp = 15.dp,
) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1100
                1f at 0
                1f at 540
                0f at 550
                0f at 1100
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "cursorAlpha",
    )
    Box(
        modifier = modifier
            .size(width, height)
            .alpha(alpha)
            .background(color, RoundedCornerShape(1.dp)),
    )
}

/**
 * A soft pulsing dot used for "live"/"online" status indicators.
 */
@Composable
fun PulsingDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.tertiary,
    size: Dp = 8.dp,
    periodMillis: Int = 1600,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier = modifier
            .size(size)
            .alpha(alpha)
            .background(color, RoundedCornerShape(percent = 50)),
    )
}
