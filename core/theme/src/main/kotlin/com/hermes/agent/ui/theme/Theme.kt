package com.hermes.agent.ui.theme
import com.hermes.agent.domain.settings.*

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.hermes.agent.core.theme.hermesColorScheme
import com.hermes.agent.core.theme.hermesTypography

/** Persisted values are retained for backup compatibility; all now render OLED monochrome. */
enum class AppTheme {
    MIDNIGHT,
    PAPER,
    HERMES_BLUE,
}

/** Root Hermes theme: shared monochrome light or OLED-black surfaces and typography. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun HermesTheme(
    appTheme: AppTheme? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontFamilyName: String = "geist",
    fontScalePercent: Int = 100,
    content: @Composable () -> Unit,
) {
    val colorScheme = hermesColorScheme(darkTheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    val context = LocalContext.current
    val fontScale = context.resources.configuration.fontScale
    val selectedTypography = hermesTypography(fontFamilyName, fontScalePercent)
    val typography = if (fontScale > 1.2f) boostedTypography(selectedTypography) else selectedTypography

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = HermesShapes,
    ) {
        HermesHighContrastWrapper(darkTheme = darkTheme, content = content)
    }
}

fun Modifier.glassBackground(
    color: Color,
    alpha: Float = 0.65f,
    blurRadius: Float = 16f,
): Modifier = this
    .blur(blurRadius.dp)
    .background(
        brush = Brush.linearGradient(
            colors = listOf(
                color.copy(alpha = alpha),
                color.copy(alpha = alpha * 0.8f),
            ),
        ),
    )

fun Modifier.premiumGradient(color: Color): Modifier = this.background(
    brush = Brush.linearGradient(
        colors = listOf(color, color.copy(alpha = 0.85f)),
    ),
)
