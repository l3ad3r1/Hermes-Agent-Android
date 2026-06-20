package com.hermes.agent.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = HermesPrimary,
    onPrimary = HermesOnPrimary,
    primaryContainer = HermesPrimaryContainer,
    onPrimaryContainer = HermesOnPrimaryContainer,
    secondary = HermesAccent,
    onSecondary = HermesOnAccent,
    secondaryContainer = HermesAccentContainer,
    background = HermesBackground,
    onBackground = HermesOnBackground,
    surface = HermesSurface,
    onSurface = HermesOnSurface,
    surfaceVariant = HermesSurfaceVariant,
    onSurfaceVariant = HermesOnSurfaceVariant,
    error = HermesError,
)

private val DarkColors = darkColorScheme(
    primary = HermesPrimaryDarkMode,
    secondary = HermesAccentDarkMode,
    background = HermesBackgroundDark,
    onBackground = HermesOnBackgroundDark,
    surface = HermesSurfaceDark,
    onSurface = HermesOnSurfaceDark,
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFFCA5A5),
)

/**
 * Hermes app theme. Falls back to Material3 dynamic color on Android 12+
 * (S24 Ultra ships with Android 14, so this is the common case), but
 * always preserves the Hermes accent color so the brand identity is
 * recognizable regardless of system wallpaper.
 */
@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HermesTypography,
        shapes = HermesShapes,
        content = content,
    )
}

private typealias Color = androidx.compose.ui.graphics.Color
