package com.hermes.agent.ui.bloub

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * The bot, drawn on a Compose canvas.
 *
 * This is the Android counterpart of `src/components/BloubBot.vue` in Bloub
 * (github.com/jeremy-prt/bloub, MIT): one filled shape morphing between fifteen
 * states, two capsules for the eyes morphing independently, on a plain
 * background. No animation library, no images, no WebView — the geometry comes
 * out of [BotEngine], which is a pure function of time.
 *
 * **The eyes are real holes punched in the body**, as on x.ai, not white shapes
 * laid on top: that is what makes them crop themselves against the silhouette
 * when they slide towards the edge. The SVG original does it with a mask; here
 * the body is filled with [ink] and the eyes are then painted in [paper] clipped
 * to the body path, which composites identically — including at partial eye
 * alpha, which a boolean path difference could not reproduce.
 *
 * [size] is the side of the whole viewBox, matching the original's `size` prop.
 * The resting ball occupies 100/158 of the half-side, so its diameter is about
 * 0.63 x [size]; the margin houses the orbit rings.
 */
@Composable
fun BloubBot(
    modifier: Modifier = Modifier,
    state: StateId = StateId.IDLE,
    size: Dp = 84.dp,
    shape: ShapeId = DEFAULT_SHAPE,
    expression: ExpressionId = DEFAULT_EXPRESSION,
    ink: Color,
    paper: Color,
    /** render one exact frame with no animation loop — how the thumbnails are drawn */
    frozenAt: Double? = null,
    /** normalised aim of a fingertip, -1..1 on each axis; null = nothing is driving the gaze */
    aim: Offset? = null,
    /**
     * Play the arrival once, on first appearance: the eyes travel a full turn
     * around the ball, so it looks like it is spinning on the spot, then settle
     * into the chosen expression. See [BloubLook.tourLook].
     */
    arrival: Boolean = false,
    /** play the measured sequence of all fourteen states on a loop */
    playSequence: Boolean = false,
    label: String? = "Hermes",
) {
    val context = LocalContext.current
    // A user who has turned animations off gets the state's most legible frame.
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val described = if (label != null) Modifier.semantics { contentDescription = label } else Modifier
    val box = modifier.size(size).then(described)

    if (frozenAt != null || reducedMotion) {
        // Pure by construction: a fresh engine sampled at one date. This is the
        // frozen state board, and it needs no clock at all.
        val frame = remember(state, shape, expression, frozenAt) {
            BotEngine(BloubFrame.RADIUS, state, shape, EXPRESSION_BY_ID[expression])
                .sample(frozenAt ?: POSES[state] ?: 1.0)
        }
        Canvas(box) { drawFrame(frame, ink, paper) }
        return
    }

    val engine = remember {
        // The ball is ROUND for the duration of the turn, whatever shape was
        // chosen, and morphs to it as it lands. Not taste: the eyes are stuck back
        // onto the real outline by `radiusAtAngle`, so on a circle the turn is
        // smooth but on a teardrop they follow the profile and hop — up to 25 px
        // of vertical deviation from the circle's trajectory.
        BotEngine(
            BloubFrame.RADIUS,
            state,
            if (arrival) ShapeId.CERCLE else shape,
            EXPRESSION_BY_ID[expression],
        )
    }
    // The clock is held outside snapshot state on purpose: the dated setters below
    // need to read it, and it changes every frame — observing it would recompose
    // the whole subtree sixty times a second.
    val clock = remember { Clock() }
    var frame by remember { mutableStateOf(engine.sample(0.0)) }
    // Read inside the frame loop rather than keyed on a LaunchedEffect: the aim
    // changes on every pointer sample, and restarting a coroutine that often to
    // hand over two floats is pure churn.
    val currentAim by rememberUpdatedState(aim)
    val currentShape by rememberUpdatedState(shape)

    LaunchedEffect(Unit) {
        var origin = 0L
        withFrameNanos { origin = it }
        while (true) {
            withFrameNanos { nanos ->
                clock.t = (nanos - origin) / 1_000_000_000.0

                if (arrival && !clock.arrivalDone) {
                    if (clock.arrivalSince < 0) {
                        clock.arrivalSince = clock.t
                        // A gaze script has to be primed DATED ONE CATCH-UP EARLIER,
                        // or the first frame comes out at the neutral gaze and the
                        // second one on the script — 127 px in a single step. The
                        // engine returns `lookPrev` until the catch-up is consumed.
                        engine.setLook(
                            BloubLook.tourLook(0.0),
                            clock.t - BotEngine.LOOK_MORPH,
                            BotEngine.LOOK_MORPH,
                        )
                    }
                    val elapsed = clock.t - clock.arrivalSince
                    if (elapsed < BloubLook.TOUR_TIME) {
                        engine.setLook(BloubLook.tourLook(elapsed), clock.t)
                    } else {
                        // The script ends at `mix: 0` and `spin: 0`, which is exactly
                        // the resting look — so handing control back is continuous,
                        // with no last slide of the eyes just as everything settles.
                        clock.arrivalDone = true
                        engine.setLook(null, clock.t)
                        engine.setShape(currentShape, clock.t)
                    }
                } else {
                    val a = currentAim
                    if (a != null) {
                        // the gaze takes hold while the travelled turn unwinds
                        if (clock.followSince < 0) clock.followSince = clock.t
                        val tour = clamp((clock.t - clock.followSince) / BloubLook.TURN_TIME)
                        engine.setLook(BloubLook.lookTarget(a.x.toDouble(), a.y.toDouble(), tour, true), clock.t)
                    } else if (clock.followSince >= 0) {
                        // released: hand the gaze back to the state's own pose
                        clock.followSince = -1.0
                        engine.setLook(null, clock.t)
                    }
                }

                frame = engine.sample(clock.t)
            }
        }
    }

    LaunchedEffect(state) { engine.setState(state, clock.t) }
    // Held back until the turn is over, so the arrival stays on the circle.
    LaunchedEffect(shape) { if (!arrival || clock.arrivalDone) engine.setShape(shape, clock.t) }
    LaunchedEffect(expression) { engine.setExpression(EXPRESSION_BY_ID[expression], clock.t) }

    // The measured montage: each state held for the duration read off the video.
    LaunchedEffect(playSequence) {
        if (!playSequence) return@LaunchedEffect
        var i = 0
        while (true) {
            val id = SEQUENCE[i % SEQUENCE.size]
            if (i % SEQUENCE.size == 0) engine.reset(id, clock.t) else engine.setState(id, clock.t)
            delay((STATE_BY_ID.getValue(id).duration * 1000).toLong())
            i++
        }
    }

    Canvas(box) { drawFrame(frame, ink, paper) }
}

/** Mutable, non-observable frame clock. See the note at its only use site. */
private class Clock {
    var t: Double = 0.0

    /** when the current follow began, or -1 while nothing is driving the gaze */
    var followSince: Double = -1.0

    /** when the arrival began, or -1 before it is primed */
    var arrivalSince: Double = -1.0

    /** the turn is over: the chosen shape is back and the gaze is the state's own */
    var arrivalDone: Boolean = false
}

/**
 * Where the bot looks when it is following a fingertip. Ported from
 * `src/ui/gaze.ts`; the angles are CHOSEN, not measured — the reference video
 * shows no pointer tracking.
 */
object BloubLook {
    /** Wide enough to read as tracking, restrained enough that no eye goes behind the limb. */
    const val YAW_MAX = 16.0
    const val PITCH_MAX = 13.0

    /** Height the gaze holds with the pointer centred: slightly above the equator. */
    const val PITCH = 10.0

    /** Where the head settles: it stops looking up-right (its resting pose) and looks left. */
    const val TURN = 26.0

    /**
     * A full turn travelled ON THE WAY: the eyes do not slide across the face,
     * they go round the ball before arriving. Free, because the eyes live on a
     * sphere — past 90 degrees of yaw they cross the limb, the engine drops them
     * from the image, then they reappear on the other side. And it LANDS RIGHT by
     * construction, -360 degrees being the same angle as 0.
     */
    const val SPIN = 360.0

    const val TURN_TIME = 1.1

    /** How long the arrival's turn takes. */
    const val TOUR_TIME = 1.5

    /**
     * "The turn": the ball looks like it is spinning on itself.
     *
     * [Look.mix] stays at ZERO from start to finish — no direction is imposed,
     * only [Look.spin] fades, which takes the eyes BEHIND the ball before
     * bringing them back exactly where the chosen expression puts them.
     *
     * Ease-in-OUT, not the exponential ease-out used everywhere else in this
     * package: this is not a value settling, it is an object turning. Under an
     * ease-out two thirds of the turn were swallowed in 0.3 s — a jolt, not a
     * rotation.
     *
     * It is VIVID crossing the limb — 20 px between two frames on a ball of
     * radius 100 — and that is not a tuning fault: near the edge a small angle
     * becomes a large on-screen displacement, and the eye disappears then
     * reappears on the other side. Slowing it down changes nothing; the
     * trajectory demands it, and it is what makes the effect. Do not soften it.
     */
    fun tourLook(t: Double): Look = Look(
        yaw = 0.0,
        pitch = 0.0,
        mix = 0.0,
        spin = SPIN * (1 - Easings.easeInOutCubic(clamp(t / TOUR_TIME))),
        wander = 1.0,
    )

    /**
     * [nx] horizontal offset of the pointer from the bot's centre, -1..1 (right
     * positive); [ny] vertical, in screen sense (down positive); [tour] arrival
     * progress 0..1; [pointer] false = no pointer known, so the head stays turned
     * but comes back to life.
     */
    fun lookTarget(nx: Double, ny: Double, tour: Double, pointer: Boolean): Look = Look(
        yaw = -TURN + nx * YAW_MAX,
        // positive pitch = looking up, whereas the screen's y goes down
        pitch = PITCH - ny * PITCH_MAX,
        mix = tour,
        spin = SPIN * (1 - tour),
        // Without a pointer the head stays turned towards the panel, but its drift
        // is given back: otherwise the bot stares at a dead point.
        wander = if (pointer) 0.0 else 1.0,
    )
}

// ───────────────────────────────────────────────────────────── drawing

private fun DrawScope.drawFrame(frame: BotFrame, ink: Color, paper: Color) {
    val side = min(size.width, size.height)
    val k = (side / (BloubFrame.HALF_VIEWBOX * 2)).toFloat()

    translate(size.width / 2f, size.height / 2f) {
        scale(k, k, pivot = Offset.Zero) {
            val body = frame.body?.let { contourPath(it) }
            val bodyAlpha = frame.bodyAlpha.toFloat()

            // The back half of the orbits: drawn before the body, so occluded by it.
            drawArcs(frame.arcs, back = true)

            // The burst particles pass behind the core.
            if (frame.dotsBehind) drawDots(frame.dots, ink, paper)

            if (body != null) {
                // An opaque ground in the body's exact shape, under the body itself:
                // a hole shows whatever is drawn behind, and the back half of the
                // rings is drawn precisely there. Without this ground, a ring passing
                // behind the ball reappears INSIDE the eyes. Filled with `paper`
                // rather than pure white, because that is exactly what the eyes used
                // to show — the page behind.
                drawPath(body, paper, alpha = bodyAlpha)
                drawPath(body, ink, alpha = bodyAlpha)

                // The holes: clipped to the body, so they crop themselves at the edge.
                clipPath(body) {
                    for (eye in frame.eyes) {
                        drawPath(eyePath(eye), paper, alpha = (eye.alpha * frame.bodyAlpha).toFloat())
                    }
                    frame.notch?.let {
                        drawCircle(paper, it.r.toFloat(), Offset(it.x.toFloat(), it.y.toFloat()), alpha = bodyAlpha)
                    }
                }
            }

            if (!frame.dotsBehind) drawDots(frame.dots, ink, paper)

            frame.notif?.let {
                drawCircle(Color(NOTIF_BLUE), it.r.toFloat(), Offset(it.x.toFloat(), it.y.toFloat()))
            }

            // The front half of the orbits.
            drawArcs(frame.arcs, back = false)
        }
    }
}

private fun contourPath(c: ClosedContour): Path = Path().apply {
    moveTo(c.startX.toFloat(), c.startY.toFloat())
    for (s in c.cubics) {
        cubicTo(
            s.c1x.toFloat(), s.c1y.toFloat(),
            s.c2x.toFloat(), s.c2y.toFloat(),
            s.x.toFloat(), s.y.toFloat(),
        )
    }
    close()
}

/**
 * The capsule at the origin, put through the eye's tangent frame.
 *
 * The engine hands back the matrix in SVG's `matrix(a, b, c, d, e, f)` order —
 * x' = ax + cy + e, y' = bx + dy + f — which maps onto Compose's column-major
 * 4x4 at indices [Matrix.ScaleX], [Matrix.SkewY], [Matrix.SkewX], [Matrix.ScaleY],
 * [Matrix.TranslateX] and [Matrix.TranslateY].
 */
private fun eyePath(eye: RenderedEye): Path {
    val cap = eye.capsule
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(
                    (-cap.halfW).toFloat(),
                    (-cap.halfH).toFloat(),
                    cap.halfW.toFloat(),
                    cap.halfH.toFloat(),
                ),
                CornerRadius(cap.r.toFloat(), cap.r.toFloat()),
            )
        )
    }
    val m = Matrix()
    m.values[Matrix.ScaleX] = eye.a.toFloat()
    m.values[Matrix.SkewY] = eye.b.toFloat()
    m.values[Matrix.SkewX] = eye.c.toFloat()
    m.values[Matrix.ScaleY] = eye.d.toFloat()
    m.values[Matrix.TranslateX] = eye.e.toFloat()
    m.values[Matrix.TranslateY] = eye.f.toFloat()
    path.transform(m)
    return path
}

private fun DrawScope.drawDots(dots: List<DotRender>, ink: Color, paper: Color) {
    for (dot in dots) {
        val fill = when {
            dot.color != null -> Color(dot.color)
            dot.depth == null -> ink
            // Depth haze: the mix happens here, which alone knows the chosen colour.
            else -> Color(mixArgb(paper.toArgb(), ink.toArgb(), dot.depth))
        }
        val poly = dot.poly
        if (poly == null) {
            drawCircle(
                fill,
                dot.r.toFloat(),
                Offset(dot.x.toFloat(), dot.y.toFloat()),
                alpha = dot.opacity.toFloat(),
            )
        } else {
            // The teardrop is declared in ball-radius units, so it is translated,
            // rotated and scaled exactly as the original's transform attribute does.
            val path = Path().apply {
                moveTo(poly[0].x.toFloat(), poly[0].y.toFloat())
                for (i in 1 until poly.size) lineTo(poly[i].x.toFloat(), poly[i].y.toFloat())
                close()
            }
            val m = Matrix().apply {
                translate(dot.x.toFloat(), dot.y.toFloat())
                rotateZ(dot.rot.toFloat())
                scale(BloubFrame.RADIUS.toFloat(), BloubFrame.RADIUS.toFloat())
            }
            path.transform(m)
            drawPath(path, fill, alpha = dot.opacity.toFloat())
        }
    }
}

private fun DrawScope.drawArcs(arcs: List<ArcRender>, back: Boolean) {
    for (arc in arcs) {
        val runs = if (back) arc.back else arc.front
        if (runs.isEmpty()) continue

        val path = Path()
        for (run in runs) {
            path.moveTo(run[0].toFloat(), run[1].toFloat())
            var i = 2
            while (i < run.size) {
                path.lineTo(run[i].toFloat(), run[i + 1].toFloat())
                i += 2
            }
        }

        val brush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color(arc.grad.stops[0]),
                0.5f to Color(arc.grad.stops[1]),
                1f to Color(arc.grad.stops[2]),
            ),
            start = Offset(arc.grad.x1.toFloat(), arc.grad.y1.toFloat()),
            end = Offset(arc.grad.x2.toFloat(), arc.grad.y2.toFloat()),
        )
        drawPath(
            path = path,
            brush = brush,
            alpha = arc.opacity.toFloat(),
            style = Stroke(width = arc.width.toFloat(), cap = StrokeCap.Round),
        )
    }
}
