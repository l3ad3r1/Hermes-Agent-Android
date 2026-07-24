package com.hermes.agent.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.provider.Settings
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Three-dot typing indicator shown while the assistant is generating.
 * The dots fade in/out sequentially to give a "thinking" feel.
 */
@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.primary,
) {
    val context = LocalContext.current
    val animatorScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val reducedMotion = animatorScale == 0f

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            var alpha by remember { mutableStateOf(0.3f) }
            LaunchedEffect(Unit) {
                if (reducedMotion) return@LaunchedEffect
                while (true) {
                    delay(120 * index.toLong())
                    alpha = 1.0f
                    delay(300)
                    alpha = 0.3f
                }
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor.copy(alpha = alpha), CircleShape),
            )
        }
    }
}

/**
 * Linear "streaming progress" text — appends a fresh word every ~80ms so
 * the chat bubble visibly fills up while tokens arrive. Used as a fallback
 * animation when actual token deltas are too slow to render smoothly.
 */
@Composable
fun StreamingTextPreview(
    fullText: String,
    modifier: Modifier = Modifier,
    speedMs: Long = 40L,
) {
    var visibleChars by remember(fullText) { mutableStateOf(0) }
    LaunchedEffect(fullText) {
        while (visibleChars < fullText.length) {
            delay(speedMs)
            visibleChars = min(visibleChars + 3, fullText.length)
        }
    }
    Text(
        text = fullText.take(visibleChars),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
    )
}
