package com.hermes.agent.ui.bloub

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The clockless engine: `sample(t)` is a pure function of time. Faithful port of
 * `src/bot/engine.ts` from Bloub (github.com/jeremy-prt/bloub, MIT).
 *
 * Practical consequence: pausing, resuming, slow motion and jumping to an
 * arbitrary date all give exactly the same image, and the rendering is testable
 * without a Compose tree.
 */

class RenderedEye(
    @JvmField val capsule: Capsule,
    /** the tangent frame, in the sense of SVG matrix(a, b, c, d, e, f) */
    @JvmField val a: Double,
    @JvmField val b: Double,
    @JvmField val c: Double,
    @JvmField val d: Double,
    @JvmField val e: Double,
    @JvmField val f: Double,
    @JvmField val alpha: Double,
)

class Circle2(@JvmField val x: Double, @JvmField val y: Double, @JvmField val r: Double)

class BotFrame(
    @JvmField val body: ClosedContour?,
    @JvmField val bodyAlpha: Double,
    @JvmField val eyes: List<RenderedEye>,
    @JvmField val dots: List<DotRender>,
    /** true = the dots pass behind the body (the burst particles) */
    @JvmField val dotsBehind: Boolean,
    @JvmField val arcs: List<ArcRender>,
    @JvmField val notif: Circle2?,
    @JvmField val notch: Circle2?,
)

/**
 * Where the bot looks when something outside drives it — a fingertip, here.
 *
 * [yaw] and [pitch] are ABSOLUTE directions that replace the pose's as [mix]
 * rises. It is the ENGINE that must do this mixing, not the caller, because only
 * it knows the pose AT THIS INSTANT. And it has to be absolute on BOTH axes:
 * relative, the eye height followed each expression's — "neutre" looks at
 * +28.6 degrees while the others sit between -9 and +9 — so the eyes dropped at
 * the first change of mood. What gives an expression its character during
 * tracking is the SHAPE of its eyes, not where it looks.
 *
 * [wander] says, separately, how much automatic drift remains. The two are not
 * the same: when the pointer moves the drift must die down, but with NO pointer
 * the head must stay turned AND keep living.
 *
 * [spin] is a turn to travel ON THE WAY, in degrees, faded to 0 on arrival.
 */
class Look(
    @JvmField val yaw: Double,
    @JvmField val pitch: Double,
    @JvmField val mix: Double,
    @JvmField val spin: Double,
    @JvmField val wander: Double,
)

private val NO_LOOK = Look(0.0, 0.0, 0.0, 0.0, 1.0)

private fun lerpLook(a: Look, b: Look, t: Double) = Look(
    yaw = lerp(a.yaw, b.yaw, t),
    pitch = lerp(a.pitch, b.pitch, t),
    mix = lerp(a.mix, b.mix, t),
    spin = lerp(a.spin, b.spin, t),
    wander = lerp(a.wander, b.wander, t),
)

/** Interpolate two poses. The decor cross-fades in opacity, not in geometry. */
private fun blendPose(a: Pose, b: Pose, t: Double): Pose {
    val out = 1 - t
    return Pose(
        sil = BloubShape.blend(a.sil, b.sil, t),
        offX = lerp(a.offX, b.offX, t),
        offY = lerp(a.offY, b.offY, t),
        gaze = HeadGaze(
            yaw = lerp(a.gaze.yaw, b.gaze.yaw, t),
            pitch = lerp(a.gaze.pitch, b.gaze.pitch, t),
            roll = lerp(a.gaze.roll, b.gaze.roll, t),
        ),
        split = lerp(a.split, b.split, t),
        eyes = arrayOf(lerpEyeCfg(a.eyes[0], b.eyes[0], t), lerpEyeCfg(a.eyes[1], b.eyes[1], t)),
        eyeAlpha = lerp(a.eyeAlpha, b.eyeAlpha, t),
        bodyAlpha = lerp(a.bodyAlpha, b.bodyAlpha, t),
        dots = a.dots.map { it.faded(out) } + b.dots.map { it.faded(t) },
        arcs = a.arcs.map { it.faded(out, "a") } + b.arcs.map { it.faded(t, "b") },
        // the pip belongs to one of the two states, it does not blend
        notif = if (t < 0.5) a.notif else b.notif,
        dotsBehind = if (t < 0.5) a.dotsBehind else b.dotsBehind,
    )
}

class BotEngine(
    /** ball radius at rest, in viewBox units */
    @JvmField val scale: Double = BloubFrame.RADIUS,
    initial: StateId = StateId.IDLE,
    shape: ShapeId? = null,
    expression: BotExpression? = null,
) {

    companion object {
        /** duration of the morph when the body's shape changes */
        const val SHAPE_MORPH: Double = 0.45

        /**
         * How long the gaze takes to catch up with its target. Shorter than
         * [SHAPE_MORPH]: a following gaze should look attentive, not viscous.
         */
        const val LOOK_MORPH: Double = 0.24
    }

    private var cur: StateId = initial
    private var prev: StateId? = null

    /**
     * FROZEN starting pose, laid down only when a state change arrives while a
     * fade is already running. See [setState].
     */
    private var departFige: Pose? = null
    private var tCur = 0.0
    private var tPrev = 0.0
    private var blinkAt = -10.0
    private val pts: MutableList<Pt> = ArrayList(BloubProfiles.SAMPLES)

    private var shapeId: ShapeId? = shape
    private var shapePrev: ShapeId? = null
    private var shapeAt = -10.0

    private var expr: BotExpression? = expression
    private var exprPrev: BotExpression? = null
    private var exprAt = -10.0

    private var look: Look = NO_LOOK
    private var lookPrev: Look = NO_LOOK
    private var lookAt = -10.0
    private var lookMorph = LOOK_MORPH

    val state: StateId get() = cur

    /**
     * Resting expression chosen in the customiser. Like the shape, it glides to
     * the new value instead of jumping.
     */
    fun setExpression(expression: BotExpression?, now: Double = 0.0) {
        if (expression === expr) return
        exprPrev = expr
        expr = expression
        exprAt = now
    }

    /** Effective expression at [now], mid-morph included. */
    private fun exprAtTime(now: Double): BotExpression? {
        val to = expr ?: return null
        val from = exprPrev ?: return to
        val k = (now - exprAt) / SHAPE_MORPH
        if (k >= 1) return to
        return blendExpression(from, to, Easings.easeOutQuint(clamp(k)))
    }

    /**
     * Shape chosen in the customiser. It replaces the body only on the resting
     * states ([StateDef.baseBody]): on the others the silhouette IS the animation
     * and must not be overwritten.
     */
    fun setShape(shape: ShapeId?, now: Double = 0.0) {
        if (shape == shapeId) return
        shapePrev = shapeId
        shapeId = shape
        shapeAt = now
    }

    private fun radiiOf(shape: ShapeId?): DoubleArray? = shape?.let { SHAPE_BY_ID[it]?.radii }

    /**
     * Effective shape at [now], mid-morph included.
     *
     * Does NOT clear [shapePrev] at the end of the morph: `sample` must stay a
     * pure function of time, so re-reading a past date must give the intermediate
     * image back.
     */
    private fun shapeAtTime(now: Double): DoubleArray? {
        val to = radiiOf(shapeId) ?: return null
        val from = radiiOf(shapePrev) ?: return to
        val k = (now - shapeAt) / SHAPE_MORPH
        if (k >= 1) return to
        val t = Easings.easeOutQuint(clamp(k))
        // allocates only during the morph
        return DoubleArray(to.size) { lerp(from.getOrElse(it) { to[it] }, to[it], t) }
    }

    /**
     * New gaze target, null to return to the state's own.
     *
     * It restarts from the CURRENT value, not from the previous target as
     * [setShape] does: this is called on every pointer move, and restarting from
     * the old target would make the gaze step back before each catch-up.
     */
    fun setLook(target: Look?, now: Double, morph: Double = LOOK_MORPH) {
        // A non-finite target is refused. The engine KEEPS the last one: a NaN laid
        // down once would propagate to every frame and the bot would never rest again.
        if (target != null &&
            !(target.yaw + target.pitch + target.mix + target.spin + target.wander).isFinite()
        ) {
            return
        }
        lookPrev = lookAtTime(now)
        look = target ?: NO_LOOK
        lookAt = now
        lookMorph = morph
    }

    /** Effective gaze at [now], catch-up included. */
    private fun lookAtTime(now: Double): Look {
        val k = (now - lookAt) / lookMorph
        if (k >= 1) return look
        return lerpLook(lookPrev, look, Easings.easeOutQuint(clamp(k)))
    }

    private fun posed(def: StateDef, t: Double, shape: DoubleArray?, expr: BotExpression?): Pose {
        var pose = def.pose(t)
        if (def.baseBody && shape != null) {
            // keep the pose (rotation, offset, squash) and swap only the profile
            pose = pose.copy(sil = pose.sil.copy(radii = shape))
        }
        if (def.baseFace && expr != null) {
            pose = pose.copy(gaze = expr.gaze, split = expr.split, eyes = expr.eyes)
        }
        return pose
    }

    /**
     * Eye offset at [now] for a given state, in ball-radius units.
     *
     * It is READ from a table and interpolated, never recomputed: [BloubEyeFit]
     * explains why that distinction is the whole fix. Here it only remains to
     * interpolate it along the shape's axis, with exactly the curve and duration
     * of the silhouette morph — same cause, so it must be the same movement.
     */
    private fun decalageAtTime(now: Double, state: StateId): BloubEyeFit.Offset2 {
        /**
         * One morph axis: the table is read on its two BOUNDS and interpolated with
         * its curve. Never on the interpolated value — that one has no identity and
         * exists in no table, and feeding it in is what made earlier versions
         * tremble.
         */
        fun surAxe(
            debut: Double,
            duree: Double,
            a: BloubEyeFit.Offset2,
            b: BloubEyeFit.Offset2,
        ): BloubEyeFit.Offset2 {
            if (a === b) return b
            val k = (now - debut) / duree
            if (k >= 1) return b
            val t = Easings.easeOutQuint(clamp(k))
            return BloubEyeFit.Offset2(lerp(a.x, b.x, t), lerp(a.y, b.y, t))
        }

        // the expression axis, for each of the two shapes in play
        fun parForme(shape: ShapeId?) = surAxe(
            exprAt,
            SHAPE_MORPH,
            BloubEyeFit.decalageDesYeux(shape, state, exprPrev?.id),
            BloubEyeFit.decalageDesYeux(shape, state, expr?.id),
        )

        // then the shape axis
        return surAxe(shapeAt, SHAPE_MORPH, parForme(shapePrev), parForme(shapeId))
    }

    /**
     * Restart on [id] with NO previous state, like a fresh engine placed on it.
     *
     * That is what "rewind" means for this engine. [setState] alone cannot do it:
     * it keeps the state being left in order to fade it, which is exactly its job
     * during playback and exactly what must not happen when returning to the start
     * of a sequence.
     */
    fun reset(id: StateId, now: Double) {
        cur = id
        prev = null
        departFige = null
        tCur = now
        tPrev = now
        blinkAt = -10.0
    }

    /**
     * Origin of the running fade: the frozen pose if there is one, otherwise the
     * state being left evaluated at its own elapsed time — so still animating,
     * which is intended.
     */
    private fun origine(now: Double, shape: DoubleArray?, expr: BotExpression?): Pose? {
        departFige?.let { return it }
        val p = prev ?: return null
        val prevDef = STATE_BY_ID.getValue(p)
        return posed(prevDef, kotlin.math.max(0.0, now - tPrev), shape, expr)
    }

    /** Composite pose at [now], running fade included. Extracted so [setState] can freeze it. */
    private fun poseComposee(now: Double): Pose {
        val def = STATE_BY_ID.getValue(cur)
        val shape = shapeAtTime(now)
        val e = exprAtTime(now)
        val pose = posed(def, kotlin.math.max(0.0, now - tCur), shape, e)
        val since = now - tCur
        if (since >= def.morph) return pose
        val origin = origine(now, shape, e) ?: return pose
        return blendPose(origin, pose, Easings.easeOutQuint(clamp(since / def.morph)))
    }

    /**
     * Dated state change.
     *
     * The engine keeps only ONE slot of history, so a change arriving mid-fade
     * used to replace the blend's origin with the FULL pose of the state being
     * left, instead of the partly blended image that was on screen. Measured on
     * `idle -> wide -> idle` at 100 ms: a 35.9 px jump against 8.0 px of normal
     * movement.
     *
     * So the current composite pose is frozen and the blend starts from it.
     * Continuous by construction, however many changes are chained.
     *
     * And ONLY in that case. Freezing on every change would stop the outgoing
     * state's animation dead for the whole fade.
     */
    fun setState(id: StateId, now: Double) {
        if (id == cur) return
        val morph = STATE_BY_ID.getValue(cur).morph
        val enPleinFondu = prev != null && now - tCur < morph
        departFige = if (enPleinFondu) poseComposee(now) else null
        prev = cur
        tPrev = tCur
        cur = id
        tCur = now
        // In the video, every shape change is masked by a blink.
        if (STATE_BY_ID[id]?.blinkIn == true) blinkAt = now
    }

    fun sample(now: Double): BotFrame {
        val r = scale
        val def = STATE_BY_ID.getValue(cur)
        val shape = shapeAtTime(now)
        val e = exprAtTime(now)
        var pose = posed(def, kotlin.math.max(0.0, now - tCur), shape, e)
        var decalage = decalageAtTime(now, cur)

        // ── transition ───────────────────────────────────────────────────
        val since = now - tCur
        // The previous state is never purged: `since < def.morph` is enough to
        // ignore it once the fade is over, and forgetting it would make the engine
        // non-replayable.
        val origin = if (since < def.morph) origine(now, shape, e) else null
        if (origin != null) {
            // Exponential ease-out: the curve measured on the video. The body has no
            // overshoot. The ratio is clamped: re-reading a date BEFORE the state
            // change would give a negative ratio, which the ease-out extrapolates.
            val ratio = Easings.easeOutQuint(clamp(since / def.morph))
            pose = blendPose(origin, pose, ratio)
            // The eye offset follows the SAME curve as the silhouette that motivates it.
            val quitte = prev
            if (quitte != null) {
                val avant = decalageAtTime(now, quitte)
                decalage = BloubEyeFit.Offset2(
                    lerp(avant.x, decalage.x, ratio),
                    lerp(avant.y, decalage.y, ratio),
                )
            }
        }

        // ── life at rest ─────────────────────────────────────────────────
        val alive = pose.eyeAlpha > 0.01
        val lk = lookAtTime(now)
        val life = liveliness(now, wander = if (alive) lk.wander else 0.0, blink = alive)

        val gaze = HeadGaze(
            // Both aims REPLACE the pose's instead of adding to them, and the turn is
            // subtracted on the way. The drift is added AFTER the mix, otherwise the
            // target would cancel it along with the pose — and it must survive a
            // turned head with no pointer.
            yaw = lerp(pose.gaze.yaw, lk.yaw, lk.mix) + life.dYaw - lk.spin,
            pitch = lerp(pose.gaze.pitch, lk.pitch, lk.mix) + life.dPitch,
            // The roll follows nothing: the bot's head is tilted -13 degrees in the
            // video, and rolling it with the pointer breaks that signature.
            roll = pose.gaze.roll + life.dRoll,
        )

        // blink triggered by the state change, on top of the schedule
        val forced = clamp((now - blinkAt) / 0.2)
        val forcedLid = if (forced < 1) kotlin.math.abs(forced * 2 - 1) else 1.0
        val lid = min(life.lid, forcedLid)

        val offX = pose.offX + life.driftX
        val offY = pose.offY + life.driftY

        // ── body ─────────────────────────────────────────────────────────
        val sil = pose.sil.copy(
            cx = pose.sil.cx + offX,
            cy = pose.sil.cy + offY,
            sy = pose.sil.sy * life.breath,
        )
        val body = BloubShape.closedContour(BloubShape.toPoints(sil, r, pts))

        // ── eyes ─────────────────────────────────────────────────────────
        // The eyes live on a sphere of radius 1; as soon as the silhouette is no
        // longer a circle they are brought back in proportion to the real radius in
        // their direction, otherwise they overflow and the mask cuts them.
        fun bodyRadius(x: Double, y: Double): Double =
            BloubShape.radiusAtAngle(pose.sil.radii, atan2(y, x) - pose.sil.rot)

        val eyes = ArrayList<RenderedEye>(2)
        if (pose.eyeAlpha > 0.01) {
            val poses = eyePoses(gaze, r, pose.split)
            for (i in 0..1) {
                val ep = poses[i]
                if (ep.depth <= 0.02) continue
                val cfg = pose.eyes[i]
                val fit = bodyRadius(ep.x, ep.y)
                // The eye's own tilt: the tangent frame is composed with a rotation in
                // the eye's plane (Basis x Rot). That is what allows mirrored tilts.
                val phi = degToRad(cfg.tilt)
                val cp = cos(phi)
                val sp = sin(phi)
                val ax = ep.a * cp + ep.c * sp
                val ay = ep.b * cp + ep.d * sp
                val cx2 = -ep.a * sp + ep.c * cp
                val cy2 = -ep.b * sp + ep.d * cp
                // The blink applies AFTER all that: it is a vertical squash on screen,
                // not one along the capsule's axis.
                val k = blinkScale(min(lid, cfg.open))
                eyes.add(
                    RenderedEye(
                        capsule = Capsule.of(cfg.w * r, cfg.h * r),
                        a = ax,
                        b = ay * k,
                        c = cx2,
                        d = cy2 * k,
                        e = ep.x * fit + (offX + decalage.x) * r,
                        f = ep.y * fit + (offY + decalage.y) * r,
                        alpha = pose.eyeAlpha * clamp(ep.depth / 0.12),
                    )
                )
            }
        }

        // ── decor ────────────────────────────────────────────────────────
        val dots = pose.dots
            .filter { it.opacity > 0.01 && it.r > 0.0005 }
            .map { it.scaled(offX, offY, r) }

        // the pip sits on the contour: it follows the shape too
        val nFit = pose.notif?.let { bodyRadius(it.x, it.y) } ?: 1.0
        val nx = pose.notif?.let { (it.x * nFit + offX) * r } ?: 0.0
        val ny = pose.notif?.let { (it.y * nFit + offY) * r } ?: 0.0
        val notif = pose.notif?.let { Circle2(nx, ny, it.r * r) }
        val notch = pose.notif?.let { Circle2(nx, ny, it.notch * r) }

        return BotFrame(
            body = body,
            bodyAlpha = pose.bodyAlpha,
            eyes = eyes,
            dots = dots,
            dotsBehind = pose.dotsBehind,
            // States declare their arcs in ball-radius units; the engine is the only
            // one that knows the viewBox scale, so it does the rasterising.
            arcs = pose.arcs
                .filter { it.opacity > 0.01 }
                .map { arcRender(it.seed, it.t, r, it.id, it.opacity) },
            notif = notif,
            notch = notch,
        )
    }
}
