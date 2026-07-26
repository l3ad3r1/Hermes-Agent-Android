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
import org.junit.Assert.assertEquals
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
        twist: Float = 0f,
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
            drawOrb(rotation = rotation, color = color, twist = twist)
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
    fun rendersTheOrbAndItsTwist() {
        val surface = HermesDark.surfaceVariant
        val primary = HermesDark.primary

        // Shipping size and density, plus one large frame for the lattice.
        listOf(0.0f, 0.125f).forEach { rotation ->
            val tag = "%03d".format((rotation * 1000).toInt())
            write("orb-$tag", render(rotation, surface, primary, px = 32 * 3, density = 3f))
        }
        write("orb-large", render(0.125f, surface, primary))

        // The twist stepped through a band turn and then a slice turn, so the
        // layers can be eyeballed rather than only asserted on.
        listOf(0.00f, 0.04f, 0.08f, 0.12f, 0.20f, 0.22f, 0.25f, 0.28f).forEach { t ->
            write("twist-%03d".format((t * 1000).toInt()), render(0.2f, surface, primary, twist = t))
        }
    }

    @Test
    fun puzzleTwistLoopsWithoutAVisibleSeam() {
        val surface = HermesDark.surfaceVariant
        val primary = HermesDark.primary

        // Each twist is a whole turn of one band, so the band ends exactly where
        // it began. That is what lets the cycle wrap without carrying any state.
        // If a twist were ever changed to a partial turn, the end of the cycle
        // would snap back to the start, and this catches that.
        val start = render(0.2f, surface, primary, twist = 0f)
        val end = render(0.2f, surface, primary, twist = 1f)
        for (x in 0 until start.width step 2) {
            for (y in 0 until start.height step 2) {
                assertEquals(
                    "twist cycle does not return to its starting pose at ($x, $y)",
                    start.getPixel(x, y),
                    end.getPixel(x, y),
                )
            }
        }
    }

    @Test
    fun twistsTurnAboutBothAxes() {
        val surface = HermesDark.surfaceVariant
        val primary = HermesDark.primary

        // Event 0 turns a horizontal band, event 1 a vertical slice. Rendered
        // at the same body rotation so the only difference is the layer moving.
        val rest = render(0.2f, surface, primary, twist = 0f)
        val band = render(0.2f, surface, primary, twist = 0.05f)
        val slice = render(0.2f, surface, primary, twist = 0.22f)

        fun movedRows(a: Bitmap, b: Bitmap): Set<Int> {
            val rows = mutableSetOf<Int>()
            for (y in 0 until a.height step 2) {
                for (x in 0 until a.width step 2) {
                    if (a.getPixel(x, y) != b.getPixel(x, y)) { rows += y / 8; break }
                }
            }
            return rows
        }
        fun movedCols(a: Bitmap, b: Bitmap): Set<Int> {
            val cols = mutableSetOf<Int>()
            for (x in 0 until a.width step 2) {
                for (y in 0 until a.height step 2) {
                    if (a.getPixel(x, y) != b.getPixel(x, y)) { cols += x / 8; break }
                }
            }
            return cols
        }

        val bandRows = movedRows(rest, band)
        val sliceRows = movedRows(rest, slice)
        val bandCols = movedCols(rest, band)
        val sliceCols = movedCols(rest, slice)

        assertTrue("the band twist moved nothing", bandRows.isNotEmpty())
        assertTrue("the slice twist moved nothing", sliceRows.isNotEmpty())

        // A band is a horizontal ring: it spans the sphere's width but only a
        // little of its height. A vertical slice is the opposite. If both twists
        // ever collapsed onto the same axis, these would look alike.
        assertTrue(
            "band twist should be wide and shallow, got ${bandCols.size} cols x ${bandRows.size} rows",
            bandCols.size > bandRows.size,
        )
        assertTrue(
            "slice twist should be tall and narrow, got ${sliceCols.size} cols x ${sliceRows.size} rows",
            sliceRows.size > sliceCols.size,
        )
    }

    @Test
    fun aTwistMovesOneBandAndLeavesTheRestAlone() {
        val surface = HermesDark.surfaceVariant
        val primary = HermesDark.primary

        // Sampled early in the first twist, while that band is mid-turn.
        val still = render(0.2f, surface, primary, twist = 0f)
        val turning = render(0.2f, surface, primary, twist = 0.05f)

        var differing = 0
        for (x in 0 until still.width step 2) {
            for (y in 0 until still.height step 2) {
                if (still.getPixel(x, y) != turning.getPixel(x, y)) differing++
            }
        }
        assertTrue("no band appears to twist at all ($differing px)", differing > 40)

        // Only one band of nine may move. Were the twist ever applied to the
        // whole body, far more of the frame would change than this allows.
        val sampled = (still.width / 2) * (still.height / 2)
        assertTrue(
            "twist disturbed too much of the sphere to be one band " +
                "($differing of $sampled sampled px)",
            differing < sampled / 6,
        )
    }

    @Test
    fun cellTonesAreStableAcrossFrames() {
        val surface = HermesDark.surfaceVariant
        val primary = HermesDark.primary

        // Each cell's tone must be a function of its index, not of time. If it
        // were re-rolled per frame the cells would flicker independently and the
        // twist would be unreadable — the tones are the only thing that makes a
        // turning layer visible at all. Same inputs, byte-identical frame.
        listOf(0f, 0.05f, 0.22f).forEach { twist ->
            val first = render(0.3f, surface, primary, twist = twist)
            val second = render(0.3f, surface, primary, twist = twist)
            for (x in 0 until first.width step 3) {
                for (y in 0 until first.height step 3) {
                    assertEquals(
                        "cell tones are not deterministic at twist=$twist ($x, $y)",
                        first.getPixel(x, y),
                        second.getPixel(x, y),
                    )
                }
            }
        }
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
