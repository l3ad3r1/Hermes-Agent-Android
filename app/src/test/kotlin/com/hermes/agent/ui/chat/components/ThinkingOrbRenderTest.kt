package com.hermes.agent.ui.chat.components

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
        orbit: Float,
        breath: Float,
        background: Color,
        color: Color,
        accent: Color,
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
            drawOrb(orbit = orbit, breath = breath, color = color, accent = accent)
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
        // Material 3 baseline surfaceVariant + primary/tertiary, both schemes.
        val darkSurface = Color(0xFF49454F)
        val darkPrimary = Color(0xFFD0BCFF)
        val darkTertiary = Color(0xFFEFB8C8)

        val lightSurface = Color(0xFFE7E0EC)
        val lightPrimary = Color(0xFF6750A4)
        val lightTertiary = Color(0xFF7D5260)

        // Quarter-turns through one full orbit, with breath swinging alongside.
        val phases = listOf(
            0.00f to 0.0f,
            0.25f to 0.5f,
            0.50f to 1.0f,
            0.75f to 0.5f,
        )

        phases.forEach { (orbit, breath) ->
            val tag = "%03d".format((orbit * 100).toInt())
            write(
                "dark-$tag",
                render(orbit, breath, darkSurface, darkPrimary, darkTertiary),
            )
            write(
                "light-$tag",
                render(orbit, breath, lightSurface, lightPrimary, lightTertiary),
            )
        }

        // The reduced-motion still frame is what users with animations off see.
        write(
            "dark-reduced-motion",
            render(0.12f, 0.5f, darkSurface, darkPrimary, darkTertiary),
        )

        // Candidate sizes at Pixel-class density (~3), i.e. what a real phone
        // actually rasterises. Rendering these at density 1 understates the
        // detail badly — 22dp is 66 physical px on such a device, not 22.
        listOf(20, 22, 26, 30, 34).forEach { dp ->
            val px = dp * 3
            write(
                "size-${dp}dp",
                render(
                    orbit = 0.25f,
                    breath = 0.5f,
                    background = darkSurface,
                    color = darkPrimary,
                    accent = darkTertiary,
                    px = px,
                    density = 3f,
                ),
            )
        }

        val frames = outputDir.listFiles { f -> f.extension == "png" }.orEmpty()
        assertTrue("expected rendered frames", frames.size >= 10)
    }

    @Test
    fun orbActuallyPaintsAndAnimates() {
        val surface = Color(0xFF49454F)
        val primary = Color(0xFFD0BCFF)
        val tertiary = Color(0xFFEFB8C8)

        val a = render(0.00f, 0.0f, surface, primary, tertiary)
        val b = render(0.50f, 1.0f, surface, primary, tertiary)

        // Centre pixel must differ from the bare surface, or nothing was drawn.
        val surfaceArgb = surface.value.toLong().let { android.graphics.Color.rgb(0x49, 0x45, 0x4F) }
        assertTrue(
            "orb did not paint over the surface",
            a.getPixel(a.width / 2, a.height / 2) != surfaceArgb,
        )

        // Opposite orbit phases must produce visibly different frames, which is
        // what proves the lobes are actually travelling.
        var differing = 0
        for (x in 0 until a.width step 4) {
            for (y in 0 until a.height step 4) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) differing++
            }
        }
        assertTrue("frames identical across orbit phases", differing > 100)
    }
}
