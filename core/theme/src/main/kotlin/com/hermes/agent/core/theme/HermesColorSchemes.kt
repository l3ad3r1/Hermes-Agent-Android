package com.hermes.agent.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Shared, app-wide OLED-dark palette: pure black surfaces, white content, grayscale depth. */
object HermesPalette {
    val Background = Color.Black
    val Surface = Color.Black
    val SurfaceLow = Color(0xFF0B0B0B)
    val SurfaceVariant = Color(0xFF181818)
    val SurfaceHigh = Color(0xFF242424)
    val OnBackground = Color(0xFFF7F7F7)
    val OnSurfaceVariant = Color(0xFFB8B8B8)
    val Outline = Color(0xFF4A4A4A)
    val OutlineVariant = Color(0xFF292929)
    val Primary = Color.White
    val OnPrimary = Color.Black
    val Container = Color(0xFF202020)
}

/** OLED-black monochrome scheme. Semantic state remains distinguishable by icon and text. */
val HermesDark: ColorScheme = darkColorScheme(
    primary = HermesPalette.Primary,
    onPrimary = HermesPalette.OnPrimary,
    primaryContainer = HermesPalette.Container,
    onPrimaryContainer = HermesPalette.OnBackground,
    secondary = Color(0xFFD8D8D8),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF202020),
    onSecondaryContainer = HermesPalette.OnBackground,
    tertiary = Color(0xFFAAAAAA),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF292929),
    onTertiaryContainer = HermesPalette.OnBackground,
    background = HermesPalette.Background,
    onBackground = HermesPalette.OnBackground,
    surface = HermesPalette.Surface,
    onSurface = HermesPalette.OnBackground,
    surfaceVariant = HermesPalette.SurfaceVariant,
    onSurfaceVariant = HermesPalette.OnSurfaceVariant,
    surfaceTint = Color.White,
    surfaceDim = Color.Black,
    surfaceBright = HermesPalette.SurfaceHigh,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = HermesPalette.SurfaceLow,
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = HermesPalette.SurfaceVariant,
    surfaceContainerHighest = HermesPalette.SurfaceHigh,
    outline = HermesPalette.Outline,
    outlineVariant = HermesPalette.OutlineVariant,
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    inversePrimary = Color(0xFF333333),
    error = Color.White,
    onError = Color.Black,
    errorContainer = Color(0xFF2A2A2A),
    onErrorContainer = Color.White,
    scrim = Color.Black,
)

/** Pure-white monochrome counterpart used when the Dark Mode toggle is off. */
val HermesLight: ColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E5E5),
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFF2E2E2E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF555555),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDDDDD),
    onTertiaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF4A4A4A),
    surfaceTint = Color.Black,
    surfaceDim = Color(0xFFD8D8D8),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE2E2E2),
    outline = Color(0xFF777777),
    outlineVariant = Color(0xFFC8C8C8),
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFFCCCCCC),
    error = Color.Black,
    onError = Color.White,
    errorContainer = Color(0xFFDDDDDD),
    onErrorContainer = Color.Black,
    scrim = Color.Black,
)

/** Both modes remain strictly monochrome; Dark Mode selects the OLED black half. */
fun hermesColorScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) HermesDark else HermesLight
