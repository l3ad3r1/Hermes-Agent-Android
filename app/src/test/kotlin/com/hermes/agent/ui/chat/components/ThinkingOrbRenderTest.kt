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
        state: OrbState = OrbState.THINKING,
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
            drawOrb(rotation = rotation, color = color, style = styleFor(state), twist = twist)
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
    fun rendersEveryState() {
        val surface = HermesDark.surfaceVariant
        val primary = HermesDark.primary

        // Two angles each, at shipping size and density, since these are meant
        // to be told apart at 32dp on a phone — not at poster size.
        OrbState.entries.forEach { state ->
            listOf(0.0f, 0.125f).forEach { rotation ->
                val tag = "%03d".format((rotation * 1000).toInt())
                write(
                    "state-${state.name.lowercase()}-$tag",
                    render(rotation, surface, primary, state, px = 32 * 3, density = 3f),
                )
            }
            // A large frame too, for inspecting the lattice itself.
            write(
                "state-${state.name.lowercase()}-large",
                render(0.125f, surface, primary, state),
            )
        }

        // The puzzle twist stepped through one event, so the layer turn can be
        // eyeballed rather than only asserted on.
        listOf(0.00f, 0.04f, 0.08f, 0.12f, 0.16f, 0.20f).forEach { t ->
            write(
                "twist-%03d".format((t * 1000).toInt()),
                render(0.2f, surface, primary, OrbState.SEARCHING, twist = t),
            )
        }
    }

    @Test
    fun everyStateLooksDifferentFromTheOthers() {
        val surface = HermesDark.surfaceVariant
        val primary = HermesDark.primary

        // Rendered at the size they actually ship at: two lattices can differ
        // on paper and still collapse to the same blob at 32dp.
        val frames = OrbState.entries.associateWith { state ->
            render(0.125f, surface, primary, state, px = 32 * 3, density = 3f)
        }

        OrbState.entries.forEach { a ->
            OrbState.entries.forEach { b ->
                if (a.ordinal >= b.ordinal) return@forEach
                val fa = frames.getValue(a)
                val fb = frames.getValue(b)
                var differing = 0
                for (x in 0 until fa.width step 2) {
                    for (y in 0 until fa.height step 2) {
                        if (fa.getPixel(x, y) != fb.getPixel(x, y)) differing++
                    }
                }
                assertTrue(
                    "$a and $b render too similarly to tell apart ($differing px differ)",
                    differing > 150,
                )
            }
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
        val start = render(0.2f, surface, primary, OrbState.SEARCHING, twist = 0f)
        val end = render(0.2f, surface, primary, OrbState.SEARCHING, twist = 1f)
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
    fun aTwistMovesOneBandAndLeavesTheRestAlone() {
        val surface = HermesDark.surfaceVariant
        val primary = HermesDark.primary

        // Sampled early in the first twist, while that band is mid-turn.
        val still = render(0.2f, surface, primary, OrbState.SEARCHING, twist = 0f)
        val turning = render(0.2f, surface, primary, OrbState.SEARCHING, twist = 0.05f)

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
    fun jitterIsStableAcrossFrames() {
        val surface = HermesDark.surfaceVariant
        val primary = HermesDark.primary

        // SOLVING and WORKING scatter their lattice. That scatter must be a
        // function of the point index, not of time — a per-frame random would
        // make every point twitch independently instead of the sphere turning
        // as a rigid body. Same rotation must give a byte-identical frame.
        listOf(OrbState.SOLVING, OrbState.WORKING).forEach { state ->
            val first = render(0.3f, surface, primary, state)
            val second = render(0.3f, surface, primary, state)
            for (x in 0 until first.width step 3) {
                for (y in 0 until first.height step 3) {
                    assertEquals(
                        "$state jitter is not deterministic at ($x, $y)",
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
