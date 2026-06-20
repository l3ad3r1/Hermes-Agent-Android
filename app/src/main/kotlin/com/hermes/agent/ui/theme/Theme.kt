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

/**
 * The three Hermes app themes derived from the brand site (hermes-agent.nousresearch.com).
 *
 * MIDNIGHT — black background, white text. Terminal/hacker aesthetic.
 * PAPER    — white background, black text. Clean editorial aesthetic.
 * HERMES_BLUE — electric blue (#3300FF) background, white text. Matches the brand site exactly.
 */
enum class AppTheme {
    MIDNIGHT,
    PAPER,
    HERMES_BLUE,
}

// ── Midnight color scheme ─────────────────────────────────────────────
private val MidnightColorScheme = darkColorScheme(
    primary              = MidnightPrimary,
    onPrimary            = MidnightOnPrimary,
    primaryContainer     = MidnightPrimaryContainer,
    onPrimaryContainer   = MidnightOnPrimaryContainer,
    secondary            = MidnightSecondary,
    onSecondary          = MidnightOnSecondary,
    secondaryContainer   = MidnightSecondaryContainer,
    background           = MidnightBackground,
    onBackground         = MidnightOnBackground,
    surface              = MidnightSurface,
    onSurface            = MidnightOnSurface,
    surfaceVariant       = MidnightSurfaceVariant,
    onSurfaceVariant     = MidnightOnSurfaceVariant,
    error                = MidnightError,
    onError              = Color.Black,
)

// ── Paper color scheme ────────────────────────────────────────────────
private val PaperColorScheme = lightColorScheme(
    primary              = PaperPrimary,
    onPrimary            = PaperOnPrimary,
    primaryContainer     = PaperPrimaryContainer,
    onPrimaryContainer   = PaperOnPrimaryContainer,
    secondary            = PaperSecondary,
    onSecondary          = PaperOnSecondary,
    secondaryContainer   = PaperSecondaryContainer,
    background           = PaperBackground,
    onBackground         = PaperOnBackground,
    surface              = PaperSurface,
    onSurface            = PaperOnSurface,
    surfaceVariant       = PaperSurfaceVariant,
    onSurfaceVariant     = PaperOnSurfaceVariant,
    error                = PaperError,
    onError              = Color.White,
)

// ── Hermes Blue color scheme ──────────────────────────────────────────
private val HermesBlueColorScheme = darkColorScheme(
    primary              = BlueBrandPrimary,
    onPrimary            = BlueBrandOnPrimary,
    primaryContainer     = BlueBrandPrimaryContainer,
    onPrimaryContainer   = BlueBrandOnPrimaryContainer,
    secondary            = BlueBrandSecondary,
    onSecondary          = BlueBrandOnSecondary,
    secondaryContainer   = BlueBrandSecondaryContainer,
    background           = BlueBrandBackground,
    onBackground         = BlueBrandOnBackground,
    surface              = BlueBrandSurface,
    onSurface            = BlueBrandOnSurface,
    surfaceVariant       = BlueBrandSurfaceVariant,
    onSurfaceVariant     = BlueBrandOnSurfaceVariant,
    error                = BlueBrandError,
    onError              = Color.White,
)

// ── Legacy light / dark (used when dynamicColor is active) ────────────
private val LightColors = lightColorScheme(
    primary              = HermesPrimary,
    onPrimary            = HermesOnPrimary,
    primaryContainer     = HermesPrimaryContainer,
    onPrimaryContainer   = HermesOnPrimaryContainer,
    secondary            = HermesAccent,
    onSecondary          = HermesOnAccent,
    secondaryContainer   = HermesAccentContainer,
    background           = HermesBackground,
    onBackground         = HermesOnBackground,
    surface              = HermesSurface,
    onSurface            = HermesOnSurface,
    surfaceVariant       = HermesSurfaceVariant,
    onSurfaceVariant     = HermesOnSurfaceVariant,
    error                = HermesError,
)

private val DarkColors = darkColorScheme(
    primary              = HermesPrimaryDarkMode,
    secondary            = HermesAccentDarkMode,
    background           = HermesBackgroundDark,
    onBackground         = HermesOnBackgroundDark,
    surface              = HermesSurfaceDark,
    onSurface            = HermesOnSurfaceDark,
    surfaceVariant       = Color(0xFF334155),
    onSurfaceVariant     = Color(0xFFCBD5E1),
    error                = Color(0xFFFCA5A5),
)

/**
 * Root theme composable. When [appTheme] is provided it overrides dynamic
 * color and system dark-mode — the user's explicit choice wins.
 */
@Composable
fun HermesTheme(
    appTheme: AppTheme? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = when (appTheme) {
        AppTheme.MIDNIGHT   -> MidnightColorScheme
        AppTheme.PAPER      -> PaperColorScheme
        AppTheme.HERMES_BLUE -> HermesBlueColorScheme
        null -> when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkColors
            else -> LightColors
        }
    }

    val isDark = appTheme != AppTheme.PAPER && (appTheme != null || darkTheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = HermesTypography,
        shapes      = HermesShapes,
        content     = content,
    )
}
