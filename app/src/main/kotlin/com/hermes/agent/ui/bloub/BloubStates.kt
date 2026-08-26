package com.hermes.agent.ui.bloub

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The fourteen states measured off the reference video, plus one interface
 * transition. Faithful port of `src/bot/states.ts` from Bloub
 * (github.com/jeremy-prt/bloub, MIT).
 *
 * The numbers here are measurements, not choices. Rounding them to friendlier
 * values breaks the resemblance.
 */

class EyeCfg(
    /** local width (the capsule's short axis), in ball-radius units */
    @JvmField val w: Double,
    /** local height (the long axis) */
    @JvmField val h: Double,
    /** 1 = open, 0 = closed */
    @JvmField val open: Double = 1.0,
    /**
     * The capsule's own tilt, in degrees, positive = the top leans right.
     * Applied AFTER the sphere's tangent frame. Without it both eyes necessarily
     * lean the same way (the head roll), and anger and sadness — which need
     * mirrored tilts — are out of reach.
     */
    @JvmField val tilt: Double = 0.0,
)

class NotifPose(
    @JvmField val x: Double,
    @JvmField val y: Double,
    @JvmField val r: Double,
    @JvmField val notch: Double,
)

class Pose(
    /** the body's silhouette, in ball-radius units */
    @JvmField val sil: Silhouette,
    /** global offset of the body AND the eyes */
    @JvmField val offX: Double = 0.0,
    @JvmField val offY: Double = 0.0,
    @JvmField val gaze: HeadGaze = REST_GAZE,
    /** half-separation of the eyes on the sphere, in degrees */
    @JvmField val split: Double = EYE_SPLIT,
    /** [inner eye, outer eye] */
    @JvmField val eyes: Array<EyeCfg> = arrayOf(EyeCfg(EYE_W, EYE_H), EyeCfg(EYE_W, EYE_H)),
    /** the eyes' opacity: used by the faceless states */
    @JvmField val eyeAlpha: Double = 1.0,
    @JvmField val bodyAlpha: Double = 1.0,
    @JvmField val dots: List<DotRender> = emptyList(),
    @JvmField val arcs: List<ArcSpec> = emptyList(),
    @JvmField val notif: NotifPose? = null,
    /** true = the decor passes behind the body (the burst particles) */
    @JvmField val dotsBehind: Boolean = false,
) {
    fun copy(
        sil: Silhouette = this.sil,
        offX: Double = this.offX,
        offY: Double = this.offY,
        gaze: HeadGaze = this.gaze,
        split: Double = this.split,
        eyes: Array<EyeCfg> = this.eyes,
        eyeAlpha: Double = this.eyeAlpha,
        bodyAlpha: Double = this.bodyAlpha,
        dots: List<DotRender> = this.dots,
        arcs: List<ArcSpec> = this.arcs,
        notif: NotifPose? = this.notif,
        dotsBehind: Boolean = this.dotsBehind,
    ) = Pose(sil, offX, offY, gaze, split, eyes, eyeAlpha, bodyAlpha, dots, arcs, notif, dotsBehind)
}

private fun pair(w: Double, h: Double): Array<EyeCfg> = arrayOf(EyeCfg(w, h), EyeCfg(w, h))

// ────────────────────────────────────────────────── non-radial shapes

/**
 * The bar of the upright exclamation mark: the convex hull of two circles.
 * Measured: top circle (0, -0.505) r 0.132, bottom circle (0, +0.130) r 0.075,
 * straight flanks. So it is tapered (top/bottom ratio 1.76).
 */
private const val BAR_UPRIGHT_CY = -0.1875

private val BAR_UPRIGHT = BloubShape.profileFromPolygon(
    BloubShape.hullOfCircles(0.0, -0.505, 0.132, 0.0, 0.13, 0.075),
    0.0,
    BAR_UPRIGHT_CY,
)

/** The tilted exclamation mark's bar: a pure capsule (constant width 0.269, length 0.776). */
private val BAR_ITALIC =
    BloubShape.profileFromPolygon(BloubShape.hullOfCircles(0.0, -0.2535, 0.1345, 0.0, 0.2535, 0.1345), 0.0, 0.0)

private fun barUpright(): Silhouette = Silhouette(BAR_UPRIGHT.copyOf(), cy = BAR_UPRIGHT_CY)

private fun barItalic(rot: Double, cx: Double, cy: Double): Silhouette =
    Silhouette(BAR_ITALIC.copyOf(), rot = rot, cx = cx, cy = cy)

/**
 * The tilted exclamation mark's dot is not a disc: it is a teardrop, round end
 * (r 0.118) on the bar's side and a drawn-out point opposite, length 0.300 along
 * the glyph's axis. Centred on the round end's barycentre.
 */
private val TEAR: List<Pt> = BloubShape.hullOfCircles(0.0, 0.0, 0.118, 0.0, 0.172, 0.012)

/**
 * The triangle does not spin on itself: its centre traces a circle of radius
 * 0.213 about the origin (measured). It is that offset which makes it look as
 * though it tumbles rather than pivots in place.
 */
private const val TRI_ORBIT = 0.213

private fun spinningTriangle(rot: Double): Silhouette = BloubShape.silhouette("triangle").also {
    it.rot = rot
    it.cx = -TRI_ORBIT * sin(rot)
    it.cy = TRI_ORBIT * cos(rot)
}

// ─────────────────────────────────────────────────────────────── states

enum class StateId(val id: String, val label: String) {
    IDLE("idle", "Idle"),
    THINKING("thinking", "Thinking"),
    WINK("wink", "Wink"),
    WIDE("wide", "Wide eyes"),
    ALERT("alert", "Alert"),
    NOTIFY("notify", "Notify"),
    EXCLAIM("exclaim", "Exclaim"),
    SLEEP("sleep", "Sleep"),
    EGG("egg", "Egg"),
    HEXAGON("hexagon", "Hexagon"),
    PLAY("play", "Play"),
    ORBIT("orbit", "Orbit"),
    BURST("burst", "Burst"),
    COMET("comet", "Comet"),

    /** an interface transition, not a catalogue animation: outside [SEQUENCE] */
    SWIRL("swirl", "Swirl"),
    ;

    companion object {
        fun fromId(id: String?): StateId? = entries.firstOrNull { it.id == id }
    }
}

class StateDef(
    @JvmField val id: StateId,
    /** how long it is held when the full sequence is played */
    @JvmField val duration: Double,
    /**
     * The duration below which the animation is cut before it resolves: the
     * exclamation mark does not come back, the body stays burst. It is read off
     * the constants in [pose], it is not chosen. Null = the state ignores time or
     * loops, so any duration suits it.
     */
    @JvmField val minDuration: Double? = null,
    /** duration of the entry morph */
    @JvmField val morph: Double,
    /** true = the entry is hidden by a blink, as in the video */
    @JvmField val blinkIn: Boolean,
    /**
     * true = the body is the "resting" silhouette, so replaceable by the shape
     * chosen in the customiser. States that draw their own shape (the exclamation
     * mark, the dots, the egg, the triangle...) are false: that shape IS the
     * animation.
     */
    @JvmField val baseBody: Boolean,
    /**
     * true = the state carries the "resting" face, so replaceable by the chosen
     * expression. Only `idle` and `swirl`: the other faced states have an
     * expression measured off the video, and that is precisely what is being
     * reproduced.
     */
    @JvmField val baseFace: Boolean,
    @JvmField val pose: (Double) -> Pose,
)

/** The pulse wave that runs through the three dots from left to right. */
private fun dotPulse(t: Double, index: Int): Double {
    val p = ((((t - index * 0.5) / 1.5) % 1) + 1) % 1
    val k = if (p < 0.5) 0.5 - 0.5 * cos(p * TAU) else 0.0
    return clamp(k * 2)
}

val STATES: List<StateDef> = listOf(
    StateDef(
        id = StateId.IDLE,
        duration = 2.4,
        morph = 0.45,
        blinkIn = false,
        baseFace = true,
        baseBody = true,
        pose = { Pose(BloubShape.circle(1.0)) },
    ),

    StateDef(
        id = StateId.THINKING,
        duration = 2.6,
        morph = 0.4,
        baseFace = false,
        baseBody = false,
        blinkIn = true,
        pose = { t ->
            val mid = dotPulse(t, 1)
            // The side dots come out of the ball's flanks: in the video they stay
            // fused with it for 1-2 frames before detaching.
            val emerge = 0.3 + 0.7 * Easings.easeOutCubic(clamp(t / 0.3))
            Pose(
                // the ball BECOMES the middle dot: the morph stays continuous
                sil = BloubShape.circle(DOT_R * (1 + (DOT_PEAK - 1) * mid)).also { it.cx = DOT_X[1] },
                eyeAlpha = 0.0,
                dots = listOf(0, 2).map { i ->
                    val k = dotPulse(t, i)
                    DotRender(
                        x = DOT_X[i] * emerge,
                        y = 0.0,
                        r = DOT_R * (1 + (DOT_PEAK - 1) * k),
                        opacity = 0.55 + 0.45 * k,
                    )
                },
            )
        },
    ),

    StateDef(
        id = StateId.WINK,
        duration = 1.6,
        morph = 0.3,
        blinkIn = true,
        baseFace = false,
        baseBody = true,
        pose = {
            Pose(
                sil = BloubShape.circle(1.0),
                gaze = HeadGaze(-5.37, 4.55, 6.7),
                split = 16.25,
                // The closed eye is not the open eye squashed: it is a horizontal
                // dash WIDER than the open eye (0.447 against 0.236).
                eyes = arrayOf(EyeCfg(0.236, 0.464), EyeCfg(0.447, 0.089)),
            )
        },
    ),

    StateDef(
        id = StateId.WIDE,
        duration = 1.8,
        morph = 0.55,
        blinkIn = true,
        baseFace = false,
        baseBody = true,
        pose = {
            Pose(
                sil = BloubShape.circle(1.0),
                gaze = HeadGaze(6.92, -21.96, 11.6),
                split = 18.43,
                eyes = pair(0.356, 0.875),
            )
        },
    ),

    StateDef(
        id = StateId.ALERT,
        duration = 2.4,
        // the exclamation mark comes back into place at 1.6 + 0.4
        minDuration = 2.0,
        morph = 0.45,
        baseFace = false,
        baseBody = false,
        blinkIn = false,
        pose = { t ->
            // Measured travel: -0.087 -> +0.732 in 1.5 s, ease-in-out, micro-overshoot.
            val p = clamp(t / 1.5)
            val travel = Easings.easeInOutCubic(p) * 0.82 - 0.087
            val back = if (t > 1.6) clamp((t - 1.6) / 0.4) else 0.0
            val x = travel * (1 - back) + 0.1 * back
            // Secondary vibration at 2.5 Hz, bar and dot in antiphase.
            val buzz = sin(t * 2.5 * TAU) * 0.005
            val tilt = degToRad(17.7)
            Pose(
                sil = barItalic(rot = tilt, cx = x, cy = -0.325 - buzz),
                eyeAlpha = 0.0,
                dots = listOf(
                    DotRender(
                        // the dot follows the glyph's axis, 0.580 from the bar's centre
                        x = x - sin(tilt) * 0.58,
                        y = -0.325 + cos(tilt) * 0.58 + buzz * 2.8,
                        r = 0.118,
                        opacity = 1.0,
                        poly = TEAR,
                        rot = radToDeg(tilt),
                    )
                ),
            )
        },
    ),

    StateDef(
        id = StateId.NOTIFY,
        duration = 2.2,
        morph = 0.5,
        blinkIn = true,
        baseFace = false,
        baseBody = true,
        pose = { t ->
            // Pop of the blue pip: peaks at +14 % around 0.3 s then settles.
            val p = clamp(t / 0.45)
            val pop = 1 + (NOTIF_POP - 1) * sin(p * PI) * (1 - p * 0.35)
            val r = NOTIF_R * (if (p < 1) pop else 1.0)
            val a = degToRad(NOTIF_ANGLE)
            Pose(
                sil = BloubShape.circle(1.0),
                // the gaze goes to the opposite side from the pip
                gaze = HeadGaze(-21.94, -5.82, -12.2),
                split = 18.89,
                eyes = pair(0.505, 0.498),
                notif = NotifPose(
                    x = cos(a) * NOTIF_DIST,
                    y = sin(a) * NOTIF_DIST,
                    r = r,
                    notch = r + NOTIF_MARGIN,
                ),
            )
        },
    ),

    StateDef(
        id = StateId.EXCLAIM,
        duration = 2.0,
        morph = 0.45,
        baseFace = false,
        baseBody = false,
        blinkIn = false,
        pose = {
            Pose(
                sil = barUpright(),
                eyeAlpha = 0.0,
                dots = listOf(DotRender(x = -0.012, y = 0.526, r = 0.113, opacity = 1.0)),
            )
        },
    ),

    StateDef(
        id = StateId.SLEEP,
        duration = 2.4,
        morph = 0.5,
        baseFace = false,
        baseBody = false,
        blinkIn = false,
        pose = { t ->
            Pose(
                // Measured vertical bounce: +-0.19 about +0.11, period 0.6 s.
                sil = BloubShape.circle(0.1585).also { it.cy = 0.11 + sin(t * (TAU / 0.6)) * 0.19 },
                eyeAlpha = 0.0,
            )
        },
    ),

    StateDef(
        id = StateId.EGG,
        duration = 1.8,
        morph = 0.4,
        baseFace = false,
        baseBody = false,
        blinkIn = true,
        pose = {
            Pose(
                sil = BloubShape.silhouette("egg"),
                gaze = HeadGaze(19.97, 26.01, -17.1),
                // the eyes draw in like the body
                split = 11.07,
                eyes = pair(0.164, 0.385),
            )
        },
    ),

    StateDef(
        id = StateId.HEXAGON,
        duration = 1.6,
        morph = 0.4,
        baseFace = false,
        baseBody = false,
        blinkIn = true,
        pose = {
            Pose(
                sil = BloubShape.silhouette("hexagon"),
                gaze = HeadGaze(23.11, 24.42, -13.3),
                split = 13.37,
                eyes = pair(0.177, 0.411),
            )
        },
    ),

    StateDef(
        id = StateId.PLAY,
        duration = 2.0,
        morph = 0.5,
        baseFace = false,
        baseBody = false,
        blinkIn = true,
        pose = { t ->
            // The triangle stays almost still while the sheaf crosses it.
            val fade = clamp(t / 0.35) * clamp((2.2 - t) / 0.5)
            Pose(
                sil = spinningTriangle(0.0),
                gaze = HeadGaze(12.0, -8.0, -6.0),
                split = 15.0,
                eyes = pair(0.18, 0.34),
                // the sheaf sweeps right to left over the triangle
                arcs = SWOOSH.mapIndexed { i, s ->
                    ArcSpec(
                        id = "sw$i",
                        seed = ArcSeed(s.a, s.k, s.tilt, s.speed, s.phase, s.sweep, s.hue, s.hueSpan, s.width, 0.45 - t * 0.42, s.cy),
                        t = t,
                        opacity = fade,
                    )
                },
            )
        },
    ),

    StateDef(
        id = StateId.ORBIT,
        duration = 3.4,
        // the body has finished relaxing from the triangle to the ball at 1.6 + 0.9
        minDuration = 2.5,
        morph = 0.6,
        baseFace = false,
        baseBody = false,
        blinkIn = false,
        pose = { t ->
            // Measured rotation: ramps over 0.35 s then 1.25 turns/s (anticlockwise).
            val ramp = Easings.easeInOutCubic(clamp(t / 0.35))
            val rot = -TAU * 1.25 * t * ramp
            // The body relaxes from the triangle towards the ball during the orbit.
            val back = Easings.easeInOutCubic(clamp((t - 1.6) / 0.9))
            val tri = spinningTriangle(rot)
            val ball = BloubShape.circle(1.0).also { it.rot = rot }
            val sil = Silhouette(
                radii = DoubleArray(BloubProfiles.SAMPLES) { i -> tri.radii[i] + (ball.radii[i] - tri.radii[i]) * back },
                rot = rot,
                cx = tri.cx * (1 - back),
                cy = tri.cy * (1 - back),
            )
            val fade = clamp(t / 0.8) * clamp((3.6 - t) / 0.9)
            Pose(
                sil = sil,
                // the eyes race round the sphere about 3x faster than the silhouette
                gaze = HeadGaze(
                    yaw = REST_GAZE.yaw + sin(t * 6.5) * 65 * (1 - back),
                    pitch = -4 + back * 32,
                    roll = -13.0,
                ),
                eyes = pair(0.18, 0.34 + back * 0.07),
                // the rings come in one at a time over 0.8 s
                arcs = RINGS.mapIndexed { i, s ->
                    ArcSpec(id = "rg$i", seed = s, t = t, opacity = fade * clamp((t - i * 0.13) / 0.3))
                },
            )
        },
    ),

    StateDef(
        /**
         * Entering the settings view.
         *
         * The ONLY state not measured off the video: it is CHOSEN. It borrows
         * orbit's vocabulary — the same rings, with their measured parameters —
         * but cuts it short: 1 s instead of 3.4, half the rings, and no triangle.
         *
         * The two true flags are the whole point of this state: `baseBody` lets the
         * chosen shape replace the body, so the view can impose the circle and the
         * pebble or the droplet MORPH into it instead of jumping; `baseFace` makes
         * it carry the resting face, so pointer tracking applies from this entry on.
         */
        id = StateId.SWIRL,
        // a little more than the gaze's turn: the eyes must be settled before the
        // rings fade out
        duration = 1.3,
        minDuration = 1.3,
        morph = 0.3,
        baseFace = true,
        baseBody = true,
        // the shape morph is hidden by a blink, as everywhere else
        blinkIn = true,
        pose = { t ->
            Pose(
                sil = BloubShape.circle(1.0),
                // three rings out of orbit's six: half the sheaf is enough to
                // recognise it, and that is as many arcs fewer to rasterise per frame
                arcs = RINGS.take(3).mapIndexed { i, s ->
                    ArcSpec(
                        id = "sw$i",
                        seed = s,
                        t = t,
                        // they come in one after another then fade before the block
                        // ends, so the return to rest happens on an already-clean image
                        opacity = clamp((t - i * 0.06) / 0.14) * clamp((1.22 - t) / 0.34),
                    )
                },
            )
        },
    ),

    StateDef(
        id = StateId.BURST,
        duration = 2.6,
        // the body is recomposed at 1.7 + 0.7
        minDuration = 2.4,
        morph = 0.4,
        baseFace = false,
        baseBody = false,
        blinkIn = false,
        pose = { t ->
            // Measured collapse: 1.0 -> 0.166 in 0.7 s, ease-out, no bounce.
            val collapse = 1 - 0.834 * Easings.easeOutQuint(clamp(t / 0.7))
            val regrow = Easings.easeOutQuint(clamp((t - 1.7) / 0.7))
            Pose(
                sil = BloubShape.circle(collapse + (1 - collapse) * regrow),
                eyeAlpha = clamp((t - 1.85) / 0.4),
                dots = particles(t, 1.0),
                dotsBehind = true,
            )
        },
    ),

    StateDef(
        id = StateId.COMET,
        duration = 2.4,
        // the dot recomposes at 1.85 + 0.6 = 2.45, i.e. 0.05 s after the video's
        // cut: that remainder finishes during the next fade, as in the reference.
        minDuration = 2.4,
        morph = 0.45,
        baseFace = false,
        baseBody = false,
        blinkIn = false,
        pose = { t ->
            val collapse = 1 - (1 - COMET_DOT) * Easings.easeOutQuint(clamp(t / 0.55))
            val regrow = Easings.easeOutQuint(clamp((t - 1.85) / 0.6))
            val fade = clamp((t - 0.15) / 0.25) * clamp((1.95 - t) / 0.3)
            Pose(
                // The dot drifts 0.035 downwards then rises again (measured wobble).
                sil = BloubShape.circle(collapse + (1 - collapse) * regrow)
                    .also { it.cy = sin(clamp(t / 1.7) * PI) * 0.035 },
                eyeAlpha = clamp((t - 2) / 0.35),
                arcs = COMET_RIBBONS.mapIndexed { i, s -> ArcSpec(id = "cm$i", seed = s, t = t, opacity = fade) },
            )
        },
    ),
)

val STATE_BY_ID: Map<StateId, StateDef> = STATES.associateBy { it.id }

/**
 * The date, in local time, at which each state reads most clearly: this is the
 * pose the thumbnails and the state board show. Deterministic, so comparable
 * from one run to the next.
 */
val POSES: Map<StateId, Double> = mapOf(
    StateId.IDLE to 1.0,
    StateId.THINKING to 1.1,
    StateId.WINK to 0.8,
    StateId.WIDE to 0.8,
    StateId.ALERT to 0.75,
    StateId.NOTIFY to 0.9,
    StateId.EXCLAIM to 0.8,
    StateId.SLEEP to 0.45,
    StateId.EGG to 0.8,
    StateId.HEXAGON to 0.8,
    StateId.PLAY to 0.9,
    StateId.ORBIT to 1.2,
    StateId.SWIRL to 0.5,
    StateId.BURST to 0.45,
    StateId.COMET to 1.15,
)

/** Playback order of the full sequence, traced on the reference video. */
val SEQUENCE: List<StateId> = listOf(
    StateId.IDLE,
    StateId.THINKING,
    StateId.WINK,
    StateId.WIDE,
    StateId.ALERT,
    StateId.NOTIFY,
    StateId.EXCLAIM,
    StateId.SLEEP,
    StateId.EGG,
    StateId.HEXAGON,
    StateId.PLAY,
    StateId.ORBIT,
    StateId.BURST,
    StateId.COMET,
)
