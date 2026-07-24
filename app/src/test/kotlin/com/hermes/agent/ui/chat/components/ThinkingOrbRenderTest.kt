package com.hermes.agent.ui.chat.components

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.hermes.agent.core.theme.HermesDark
import com.hermes.agent.core.theme.HermesLight
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Rasterises [drawOrb] at fixed animation phases so the orb can be eyeballed
 * without an emulator — the app ships arm64-v8a native libs only, so it cannot
 * be installed on an x86_64 AVD, but the orb itself is pure Compose drawing.
 *
 * Frames land in `build/orb-frames/`. Rendered at 8x the real 22dp so the
 * gradients are actually inspectable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThinkingOrbRenderTest {

    private val outputDir = File("build/orb-frames").apply { mkdirs() }

    private fun render(
        rotation: Float,
        background: Color,
        color: Color,
        px: Int = 176,
        density: Float = 1f,
    ): Bitmap {
        val image = ImageBitmap(px, px)
        CanvasDrawScope().draw(
            density = Density(density),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(image),
            size = Size(px.toFloat(), px.toFloat()),
        ) {
            // Stand in for the chat bubble's surfaceVariant, so the orb is
            // judged against the surface it actually sits on.
            drawRect(background)
            drawOrb(rotation = rotation, color = color)
        }
        return image.asAndroidBitmap()
    }

    private fun write(name: String, bitmap: Bitmap) {
        File(outputDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun rendersOrbitSweepOnDarkAndLightSurfaces() {
        // Hermes's own schemes, taken from HermesColorSchemes. These must not be
        // swapped for the Material 3 baseline: the app is a deliberately
        // monochrome OLED palette, so the baseline purple renders a far more
        // colourful orb than any user ever sees.
        val darkSurface = HermesDark.surfaceVariant
        val darkPrimary = HermesDark.primary

        val lightSurface = HermesLight.surfaceVariant
        val lightPrimary = HermesLight.primary

        // Eighth-turns, so the point bands can be checked for strobing — a
        // rotating lattice can appear to jump backwards at some step sizes.
        listOf(0.00f, 0.125f, 0.25f, 0.375f, 0.50f).forEach { rotation ->
            val tag = "%03d".format((rotation * 1000).toInt())
            write("dark-$tag", render(rotation, darkSurface, darkPrimary))
            write("light-$tag", render(rotation, lightSurface, lightPrimary))
        }

        // The reduced-motion still frame is what users with animations off see.
        write("dark-reduced-motion", render(0.15f, darkSurface, darkPrimary))

        // Candidate sizes at Pixel-class density (~3), i.e. what a real phone
        // actually rasterises. Rendering these at density 1 understates the
        // detail badly — 28dp is 84 physical px on such a device, not 28.
        // Point count has to survive this: too many dots at 28dp turn to mush.
        listOf(22, 28, 34, 40).forEach { dp ->
            write(
                "size-${dp}dp",
                render(
                    rotation = 0.125f,
                    background = darkSurface,
                    color = darkPrimary,
                    px = dp * 3,
                    density = 3f,
                ),
            )
        }

        val frames = outputDir.listFiles { f -> f.extension == "png" }.orEmpty()
        assertTrue("expected rendered frames", frames.size >= 10)
    }

    @Test
    fun orbActuallyPaintsAndAnimates() {
        val surface = HermesDark.surfaceVariant
        val primary = HermesDark.primary

        val a = render(0.00f, surface, primary)
        val b = render(0.125f, surface, primary)

        // Something must have been painted over the bare surface.
        val surfaceArgb = surface.toArgb()
        var painted = 0
        for (x in 0 until a.width step 2) {
            for (y in 0 until a.height step 2) {
                if (a.getPixel(x, y) != surfaceArgb) painted++
            }
        }
        assertTrue("orb did not paint over the surface", painted > 50)

        // A partial turn must move the points. Checked at an eighth rather than
        // a half turn: the point lattice is near-symmetric under 180 degrees,
        // so a half turn can look deceptively similar and would weaken this.
        var differing = 0
        for (x in 0 until a.width step 2) {
            for (y in 0 until a.height step 2) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) differing++
            }
        }
        assertTrue("frames identical across rotation", differing > 100)
    }
}
