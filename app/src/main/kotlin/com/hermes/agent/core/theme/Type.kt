package com.hermes.agent.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hermes.agent.core.theme.R


/**
 * Geist + Geist Mono — the Hermes Agent design system typefaces
 * (matches the Nous design: Geist for UI, Geist Mono for code/metadata).
 * Font files are bundled under res/font.
 */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
)

/**
 * Rubik and IBM Plex Sans, offered alongside Geist in Appearance settings.
 *
 * Both ship as single variable fonts rather than one file per weight, so the
 * weight axis has to be driven explicitly through [FontVariation]; supplying
 * only a [FontWeight] would load every weight at the file's default and every
 * style would render identically. Rubik's wght axis starts at 300 and IBM Plex
 * Sans' tops out at 700, so requested weights are clamped into each font's own
 * range instead of being passed through blind.
 *
 * Licensed under the SIL Open Font License 1.1 (see licenses/fonts/), which is
 * independent of this project's own licence.
 */
@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight, min: Int, max: Int): Font {
    val axis = weight.weight.coerceIn(min, max)
    return Font(
        resId,
        FontWeight(axis),
        variationSettings = FontVariation.Settings(FontVariation.weight(axis)),
    )
}

private fun rubik(weight: FontWeight) = variableFont(R.font.rubik_variable, weight, 300, 900)

private fun ibmPlexSans(weight: FontWeight) =
    variableFont(R.font.ibm_plex_sans_variable, weight, 100, 700)

val Rubik = FontFamily(
    rubik(FontWeight.Normal),
    rubik(FontWeight.Medium),
    rubik(FontWeight.SemiBold),
    rubik(FontWeight.Bold),
)

val IbmPlexSans = FontFamily(
    ibmPlexSans(FontWeight.Normal),
    ibmPlexSans(FontWeight.Medium),
    ibmPlexSans(FontWeight.SemiBold),
    ibmPlexSans(FontWeight.Bold),
)

/** Outfit — a rounded geometric sans (SIL OFL 1.1, see licenses/fonts/). */
@OptIn(ExperimentalTextApi::class)
val Outfit = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
        Font(
            com.hermes.agent.R.font.outfit_variable,
            w,
            variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)),
        )
    },
)

/**
 * Every size is a term of one geometric sequence anchored at body = 16sp with ratio 1.2, so the
 * hierarchy has a steady rhythm instead of hand-picked sizes: 11, 13, 16, 19, 23, 28, 33, 40, 48, 57.
 * Line heights widen for reading text and tighten for large headings. Used with the colour presets.
 */
val GeometricTypography = Typography(
    displayLarge = TextStyle(fontFamily = Geist, fontSize = 57.sp, lineHeight = 66.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = Geist, fontSize = 48.sp, lineHeight = 55.sp),
    displaySmall = TextStyle(fontFamily = Geist, fontSize = 40.sp, lineHeight = 46.sp),
    headlineLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 33.sp, lineHeight = 41.sp),
    headlineMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 35.sp),
    headlineSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp),
    titleLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Geist, fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)

val HermesTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 60.sp,
        letterSpacing = (-0.03).em,
    ),
    displayMedium = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.03).em,
    ),
    headlineLarge = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.02).em,
    ),
    headlineMedium = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.02).em,
    ),
    titleLarge = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.02).em,
    ),
    titleMedium = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.01).em,
    ),
    bodyLarge = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = GeistMono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.06.em,
    ),
)
/** Applies the user's validated family and size choice to every Material text role. */
fun hermesTypography(
    fontFamilyName: String,
    scalePercent: Int,
    base: Typography = HermesTypography,
): Typography {
    val family = when (fontFamilyName) {
        "system" -> FontFamily.SansSerif
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "rubik" -> Rubik
        "ibm_plex" -> IbmPlexSans
        "outfit" -> Outfit
        else -> Geist
    }
    val scale = scalePercent.coerceIn(85, 130) / 100f
    fun TextStyle.adjusted(): TextStyle = copy(
        fontFamily = family,
        fontSize = (fontSize.value * scale).sp,
        lineHeight = (lineHeight.value * scale).sp,
    )
    return Typography(
        displayLarge = base.displayLarge.adjusted(),
        displayMedium = base.displayMedium.adjusted(),
        displaySmall = base.displaySmall.adjusted(),
        headlineLarge = base.headlineLarge.adjusted(),
        headlineMedium = base.headlineMedium.adjusted(),
        headlineSmall = base.headlineSmall.adjusted(),
        titleLarge = base.titleLarge.adjusted(),
        titleMedium = base.titleMedium.adjusted(),
        titleSmall = base.titleSmall.adjusted(),
        bodyLarge = base.bodyLarge.adjusted(),
        bodyMedium = base.bodyMedium.adjusted(),
        bodySmall = base.bodySmall.adjusted(),
        labelLarge = base.labelLarge.adjusted(),
        labelMedium = base.labelMedium.adjusted(),
        labelSmall = base.labelSmall.adjusted(),
    )
}
