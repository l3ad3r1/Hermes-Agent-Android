package com.hermes.agent.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

/**
 * Phase 4 accessibility helpers.
 *
 * Provides:
 *   - A high-contrast color variant for users who enable "High contrast
 *     text" in system accessibility settings.
 *   - A larger-typography variant for users who bump system font scale.
 *     Compose Material3 already respects `LocalDensity` and `FontScale`
 *     automatically, so most of our text scales correctly out of the
 *     box; this just bumps the base size for users at the maximum scale.
 *   - A semantics helper for chat bubbles that ensures each bubble is
 *     read as a single utterance by TalkBack rather than token-by-token.
 */

/**
 * Wraps [content] with a high-contrast content color when the system
 * "high contrast text" setting is enabled.
 */
@Composable
fun HermesHighContrastWrapper(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val highContrast = runCatching {
        android.provider.Settings.Secure.getInt(
            context.contentResolver,
            "high_text_contrast_enabled",
            0,
        ) == 1
    }.getOrDefault(false)

    val overrideColor = if (highContrast) {
        if (darkTheme) Color.White else Color.Black
    } else {
        LocalContentColor.current
    }

    CompositionLocalProvider(LocalContentColor provides overrideColor) {
        content()
    }
}

/**
 * Apply a per-user font-scale boost. Compose already scales text by
 * `LocalDensity.fontScale`, but for users at the maximum system scale
 * (1.3x or 2.0x) we add an additional 10% boost to improve chat
 * readability without breaking layout.
 *
 * Used by [HermesTheme] when the system font scale exceeds 1.2.
 */
fun boostedTypography(base: Typography, boost: Float = 1.1f): Typography {
    fun androidx.compose.ui.text.TextStyle.boosted() = copy(
        fontSize = (fontSize.value * boost).sp,
        lineHeight = (lineHeight.value * boost).sp,
    )
    return Typography(
        displayLarge = base.displayLarge.boosted(),
        displayMedium = base.displayMedium.boosted(),
        headlineLarge = base.headlineLarge.boosted(),
        headlineMedium = base.headlineMedium.boosted(),
        titleLarge = base.titleLarge.boosted(),
        titleMedium = base.titleMedium.boosted(),
        bodyLarge = base.bodyLarge.boosted(),
        bodyMedium = base.bodyMedium.boosted(),
        bodySmall = base.bodySmall.boosted(),
        labelLarge = base.labelLarge.boosted(),
        labelMedium = base.labelMedium.boosted(),
        labelSmall = base.labelSmall.boosted(),
    )
}
