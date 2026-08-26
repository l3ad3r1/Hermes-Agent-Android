package com.hermes.agent.ui.bloub

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where to sit the face on a customiser shape. Faithful port of
 * `src/bot/eyefit.ts` from Bloub (github.com/jeremy-prt/bloub, MIT).
 *
 * The eyes live on a sphere, and [BloubShape.radiusAtAngle] re-seats them on the
 * real contour in proportion to the local radius. That proportion places their
 * CENTRE correctly, but an eye has a size: the margin left in front of the edge
 * is scaled by the same factor, so a silhouette that is narrow in that direction
 * pushes the eye against the edge until the mask opens it outwards. The capsule
 * showed up as a notch in the body on `capsule`, `triangle`, `nuage` and
 * `goutte`.
 *
 * This module solves the problem ONCE and hands back a table of offsets. That
 * choice is the essence of the fix, far more than the geometry that follows:
 * solved inside the render loop, the correction reacts to everything that moves
 * at sixty frames a second — the gaze drift, the expression mid-morph, which
 * edge is nearest, which eye is most constrained — and every such variant
 * produced a visible movement artefact. The rest of the engine does not work
 * that way: poses are DECLARED and it only interpolates them along known curves.
 * A tabulated offset fits that mould, and interpolating between two constants is
 * monotone by construction.
 *
 * **Deviation from the original:** the table is built lazily, one shape at a
 * time, instead of eagerly at import. The values are identical — the solver is
 * deterministic and each shape is independent — but the default circle needs no
 * correction at all, so the common path costs nothing and a shape the user never
 * picks is never solved.
 */
object BloubEyeFit {

    /** The solver's reference radius. The offset returned is in units of this radius. */
    private const val R = 100.0

    /**
     * Maximum amplitudes of the resting life, read off [liveliness]: `loopNoise`
     * is bounded by 1 in absolute value, so these sums are exact bounds and not
     * estimates.
     *
     * They must be covered, otherwise the correction is right on the nominal pose
     * and wrong a second later: 7 degrees of yaw move the eye a dozen units on a
     * ball of radius 100.
     */
    private const val DERIVE_YAW = 5.5 + 1.6
    private const val DERIVE_PITCH = 4.2 + 1.3

    /** Float of the centre, in ball-radius units. */
    private const val DERIVE_X = 0.006
    private const val DERIVE_Y = 0.007

    /**
     * Float of the centre, in viewBox units. It is added to the capsule's radius:
     * under one unit, so absorbing it this way costs less than multiplying the
     * trials by its four corners.
     */
    private val FLOTTEMENT = sqrt(DERIVE_X * DERIVE_X + DERIVE_Y * DERIVE_Y) * R

    /** Directions probed, and the bisection step. Their product is the build cost. */
    private const val DIRECTIONS = 12
    private const val DICHOTOMIE = 8

    private val NUL = Offset2(0.0, 0.0)

    class Offset2(@JvmField val x: Double, @JvmField val y: Double)

    /** A pose's face: what the solver needs to place its capsules. */
    private class Visage(val gaze: HeadGaze, val split: Double, val eyes: Array<EyeCfg>)

    /**
     * A capsule ready to be measured: the segment of its axis, and enough to work
     * out the radius to clear IN A GIVEN DIRECTION.
     *
     * A capsule is exactly a segment thickened by a disc of radius [r]. Its image
     * under the tangent matrix is therefore a segment thickened by an ELLIPSE, and
     * the radius to clear depends on the direction: it is that ellipse's support
     * function, `r * |A^T u|`.
     */
    private class Empreinte(
        val x: Double,
        val y: Double,
        val ax: Double,
        val ay: Double,
        val r: Double,
        val m0: Double,
        val m1: Double,
        val m2: Double,
        val m3: Double,
    )

    /**
     * Footprints of a face's two eyes, laid on a profile.
     *
     * The blink is not in it: a closed eye does not need room made for it.
     */
    private fun empreintes(visage: Visage, sil: Silhouette, radii: DoubleArray): List<Empreinte> {
        val out = ArrayList<Empreinte>(2)
        val poses = eyePoses(visage.gaze, R, visage.split)
        for (i in 0..1) {
            val e = poses[i]
            if (e.depth <= 0.02) continue
            val cfg = visage.eyes[i]
            val phi = degToRad(cfg.tilt)
            val cp = cos(phi)
            val sp = sin(phi)
            val ax = e.a * cp + e.c * sp
            val ay = e.b * cp + e.d * sp
            val cx = -e.a * sp + e.c * cp
            val cy = -e.b * sp + e.d * cp

            val hw = max(cfg.w * R, 0.01) / 2
            val hh = max(cfg.h * R, 0.01) / 2
            val r = min(hw, hh)
            // the axis is that of the larger dimension
            val long = hh > hw
            val demi = if (long) hh - r else hw - r
            // the local-radius proportion, exactly as the engine does it
            val fit = BloubShape.radiusAtAngle(radii, atan2(e.y, e.x) - sil.rot)
            out.add(
                Empreinte(
                    x = e.x * fit,
                    y = e.y * fit,
                    ax = (if (long) cx else ax) * demi,
                    ay = (if (long) cy else ay) * demi,
                    r = r,
                    m0 = ax,
                    m1 = ay,
                    m2 = cx,
                    m3 = cy,
                )
            )
        }
        return out
    }

    private class Approche(val d: Double, val ux: Double, val uy: Double)

    /**
     * Closest approach between a contour and a segment: the distance, and the
     * vector that goes from the contour towards the segment — the direction that
     * clears it. Both come out of the SAME pass; computing them separately doubled
     * this module's only real cost, which is that sweep.
     */
    private fun approche(pts: List<Pt>, x0: Double, y0: Double, x1: Double, y1: Double): Approche {
        val sx = x1 - x0
        val sy = y1 - y0
        val len2 = sx * sx + sy * sy
        var best = Double.POSITIVE_INFINITY
        var vx = 0.0
        var vy = 0.0
        for (p in pts) {
            var t = if (len2 > 0) ((p.x - x0) * sx + (p.y - y0) * sy) / len2 else 0.0
            t = if (t < 0) 0.0 else if (t > 1) 1.0 else t
            val ex = x0 + t * sx - p.x
            val ey = y0 + t * sy - p.y
            val d2 = ex * ex + ey * ey
            if (d2 < best) {
                best = d2
                vx = ex
                vy = ey
            }
        }
        val d = sqrt(best)
        return Approche(d, if (d > 1e-9) vx / d else 0.0, if (d > 1e-9) vy / d else 0.0)
    }

    /** A trial: capsules to fit inside a contour, and the reference contour. */
    private class Epreuve(
        val empreintes: List<Empreinte>,
        val reference: List<Empreinte>,
        val contour: List<Pt>,
        val calContour: List<Pt>,
    )

    private class Pire(val marge: Double, val ux: Double, val uy: Double)

    /** Margin of the tightest capsule, and the direction that clears it. */
    private fun pire(pts: List<Pt>, emps: List<Empreinte>, tx: Double, ty: Double): Pire {
        var marge = Double.POSITIVE_INFINITY
        var ux = 0.0
        var uy = 0.0
        for (e in emps) {
            val x = e.x + tx
            val y = e.y + ty
            val a = approche(pts, x - e.ax, y - e.ay, x + e.ax, y + e.ay)
            // support function of the ellipse in the approach's direction
            val rayon = e.r * sqrt(
                (e.m0 * a.ux + e.m1 * a.uy).let { it * it } + (e.m2 * a.ux + e.m3 * a.uy).let { it * it }
            ) + FLOTTEMENT
            if (a.d - rayon < marge) {
                marge = a.d - rayon
                ux = a.ux
                uy = a.uy
            }
        }
        return Pire(marge, ux, uy)
    }

    /**
     * The offset to apply to both eyes for this shape, state and expression.
     *
     * A TRANSLATION common to both eyes, so an isometry: separation, sizes and
     * tilts are preserved to the pixel. The face is simply seated a little lower
     * on a body that has no room at the top, which is the gesture one would make
     * by hand.
     *
     * DIRECTIONAL SEARCH, not descent. We want the smallest-norm translation that
     * fits, so a ring of directions is probed and the distance along each is
     * bisected. A gradient descent was written first and does not converge:
     * clearing the pair from one edge moves it towards the other.
     */
    private fun resous(epreuves: List<Epreuve>): Offset2 {
        if (epreuves.isEmpty()) return NUL

        /** The tightest margin over every trial, for a given translation. */
        fun marge(tx: Double, ty: Double): Double {
            var m = Double.POSITIVE_INFINITY
            for (ep in epreuves) m = min(m, pire(ep.contour, ep.empreintes, tx, ty).marge)
            return m
        }

        // Margin required: the tightest the original profile tolerates, over every
        // trial. Then capped by the most clearance the shape can offer the pair,
        // which is at its centre.
        var requis = Double.POSITIVE_INFINITY
        for (ep in epreuves) {
            requis = min(requis, pire(ep.calContour, ep.reference, 0.0, 0.0).marge)
        }

        // The travel must be able to reach the body's centre: `wide` has capsules
        // 87 units long, and on a triangle they only fit around the middle, some
        // fifty units from their nominal place. A fixed travel left them outside.
        var mx = 0.0
        var my = 0.0
        val emps = epreuves[0].empreintes
        for (e in emps) {
            mx -= e.x / emps.size
            my -= e.y / emps.size
        }
        val course = max(0.35 * R, sqrt(mx * mx + my * my) * 1.25)

        // Cap on the demand: what the shape offers at its centre, always reachable.
        requis = min(requis, marge(mx, my))

        // Already good: the circle's case, and any wide-enough shape. The capsule
        // must FIT as well as being no tighter than on the original profile —
        // without that second condition, a shape where nothing fits satisfies the
        // first degenerately and we used to give up.
        val depart = marge(0.0, 0.0)
        if (depart >= requis && depart >= 0) return NUL
        val cible = max(requis, 0.0)

        var meilleurX = 0.0
        var meilleurY = 0.0
        var meilleureNorme = Double.POSITIVE_INFINITY
        // fallback when nothing fits: the translation that clears the most
        var secoursX = 0.0
        var secoursY = 0.0
        var secours = depart

        for (d in 0 until DIRECTIONS) {
            val a = (d.toDouble() / DIRECTIONS) * TAU
            val ux = cos(a)
            val uy = sin(a)
            if (marge(ux * course, uy * course) < cible) {
                // no solution that way, but perhaps a better clearance
                for (k in doubleArrayOf(0.3, 0.6, 1.0)) {
                    val m = marge(ux * course * k, uy * course * k)
                    if (m > secours) {
                        secours = m
                        secoursX = ux * course * k
                        secoursY = uy * course * k
                    }
                }
                continue
            }
            // the shortest distance that fits, along this direction
            var bas = 0.0
            var haut = course
            repeat(DICHOTOMIE) {
                val mid = (bas + haut) / 2
                if (marge(ux * mid, uy * mid) >= cible) haut = mid else bas = mid
            }
            if (haut < meilleureNorme) {
                meilleureNorme = haut
                meilleurX = ux * haut
                meilleurY = uy * haut
            }
        }

        val x = if (meilleureNorme == Double.POSITIVE_INFINITY) secoursX else meilleurX
        val y = if (meilleureNorme == Double.POSITIVE_INFINITY) secoursY else meilleurY
        // returned in BALL-RADIUS units: the engine puts it back to its scale
        return Offset2(round6(x / R), round6(y / R))
    }

    private fun round6(v: Double): Double = (v * 1_000_000.0).roundToLong() / 1_000_000.0

    /** The face to cover: the expression's if the state accepts one, its own otherwise. */
    private fun visageDe(def: StateDef, pose: Pose, expr: BotExpression?): Visage =
        if (def.baseFace && expr != null) Visage(expr.gaze, expr.split, expr.eyes)
        else Visage(pose.gaze, pose.split, pose.eyes)

    /** The dates to sample in a state: only one if its pose does not move. */
    private fun dates(def: StateDef): DoubleArray {
        fun signature(p: Pose): String = buildString {
            append(p.gaze.yaw).append(',').append(p.gaze.pitch).append(',').append(p.gaze.roll).append('|')
            append(p.split).append('|')
            for (e in p.eyes) append(e.w).append(',').append(e.h).append(',').append(e.open).append(',').append(e.tilt).append(';')
            append('|').append(p.sil.rot).append(',').append(p.sil.cx).append(',').append(p.sil.cy)
            append(',').append(p.sil.sx).append(',').append(p.sil.sy)
        }
        if (signature(def.pose(0.0)) == signature(def.pose(def.duration))) return doubleArrayOf(0.0)
        val n = 3
        return DoubleArray(n) { (it.toDouble() / (n - 1)) * def.duration }
    }

    /** A shape's offset on one state and expression, drift included. */
    private fun decalagePour(def: StateDef, radii: DoubleArray, expr: BotExpression?): Offset2 {
        val epreuves = ArrayList<Epreuve>(12)
        for (t in dates(def)) {
            val pose = def.pose(t)
            val contour = BloubShape.toPoints(pose.sil.copy(radii = radii), R)
            val calContour = BloubShape.toPoints(pose.sil, R)
            val v = visageDe(def, pose, expr)
            // The drift's four corners bound the nominal pose, which is their
            // centre: testing it as well would change no margin.
            for (dy in doubleArrayOf(-DERIVE_YAW, DERIVE_YAW)) {
                for (dp in doubleArrayOf(-DERIVE_PITCH, DERIVE_PITCH)) {
                    val c = Visage(
                        HeadGaze(v.gaze.yaw + dy, v.gaze.pitch + dp, v.gaze.roll),
                        v.split,
                        v.eyes,
                    )
                    epreuves.add(
                        Epreuve(
                            empreintes = empreintes(c, pose.sil, radii),
                            reference = empreintes(c, pose.sil, pose.sil.radii),
                            contour = contour,
                            calContour = calContour,
                        )
                    )
                }
            }
        }
        return resous(epreuves)
    }

    /** Key of an entry: the shape, the state, and the expression when the state accepts one. */
    private data class Clef(val shape: ShapeId, val state: StateId, val expr: ExpressionId?)

    private val cache = HashMap<Clef, Offset2>()

    /**
     * Offset to apply to both eyes for this shape in this state, in ball-radius
     * units — the engine puts it back to its scale.
     *
     * Zero for the circle, on which both profiles are the same, so the margin is
     * already the one required and the search exits on the first pass. The shape
     * measured off the video therefore does not move, with no special case. Zero
     * too for a state that draws its own body: the customiser's shape does not
     * reach it, so there is nothing to correct.
     *
     * **Deviation from the original:** entries are solved one at a time on first
     * use rather than as a whole table per shape. The values are identical — each
     * entry is independent and the solver is deterministic — but the customiser
     * shows eight shapes at once, and solving eight full tables (37 entries each)
     * on the way into the screen is a visible stall where eight single entries are
     * not.
     */
    fun decalageDesYeux(shape: ShapeId?, state: StateId, expr: ExpressionId?): Offset2 {
        if (shape == null || shape == ShapeId.CERCLE) return NUL
        val def = STATE_BY_ID[state] ?: return NUL
        if (!def.baseBody) return NUL
        // Only `idle` and `swirl` carry the resting face, so only they vary by
        // expression; the other base-body states have a face measured off the video
        // and a single entry whatever the expression.
        val key = Clef(shape, state, if (def.baseFace) expr else null)
        synchronized(cache) { cache[key] }?.let { return it }
        val solved = decalagePour(def, SHAPE_BY_ID.getValue(shape).radii, key.expr?.let { EXPRESSION_BY_ID[it] })
        synchronized(cache) { cache[key] = solved }
        return solved
    }
}
