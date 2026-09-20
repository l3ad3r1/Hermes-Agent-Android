package com.hermes.agent.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot

/**
 * A whole Material 3 palette grown from one seed colour, so every surface, container, button and
 * outline is tinted to match instead of Hermes's plain monochrome.
 *
 * The idea, the eight seed colours and the tonal-spot scheme are how Agora
 * (github.com/newo-ether/Agora, MIT) themes its UI; this is our own implementation of it.
 */
enum class SeedPreset(val storageKey: String, val label: String, val seed: Color) {
    MIDNIGHT("midnight", "Midnight", Color(0xFF1A237E)),
    NORDIC("nordic", "Nordic", Color(0xFF546E7A)),
    FOREST("forest", "Forest", Color(0xFF2E7D32)),
    SUNSET("sunset", "Sunset", Color(0xFFE65100)),
    ROSE("rose", "Rose", Color(0xFFAD1457)),
    LAVENDER("lavender", "Lavender", Color(0xFF7B1FA2)),
    SLATE("slate", "Slate", Color(0xFF455A64)),
    OCEAN("ocean", "Ocean", Color(0xFF0277BD)),
    ;

    companion object {
        /** Null for "no preset" (the stored value is empty) or a key this build does not know. */
        fun fromStorageKey(key: String?): SeedPreset? = entries.firstOrNull { it.storageKey == key }
    }
}

fun seedColorScheme(preset: SeedPreset, dark: Boolean): ColorScheme =
    SchemeTonalSpot(Hct.fromInt(preset.seed.toArgb()), dark, 0.0).toColorScheme()

private fun DynamicScheme.toColorScheme(): ColorScheme {
    val c = { argb: Int -> Color(argb) }
    return ColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        inversePrimary = c(inversePrimary),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        surfaceTint = c(surfaceTint),
        inverseSurface = c(inverseSurface), inverseOnSurface = c(inverseOnSurface),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        outline = c(outline), outlineVariant = c(outlineVariant),
        scrim = c(scrim),
        surfaceBright = c(surfaceBright), surfaceDim = c(surfaceDim),
        surfaceContainer = c(surfaceContainer),
        surfaceContainerHigh = c(surfaceContainerHigh),
        surfaceContainerHighest = c(surfaceContainerHighest),
        surfaceContainerLow = c(surfaceContainerLow),
        surfaceContainerLowest = c(surfaceContainerLowest),
    )
}

/** Big soft corners: cards, sheets and fields read as pills and rounded slabs. */
val SeedShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)
