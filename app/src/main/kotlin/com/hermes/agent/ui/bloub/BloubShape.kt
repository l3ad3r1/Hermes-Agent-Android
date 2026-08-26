package com.hermes.agent.ui.bloub

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Silhouette geometry. Faithful port of `src/bot/shape.ts` from Bloub
 * (github.com/jeremy-prt/bloub, MIT).
 *
 * The one departure from the original is the output type: where the TypeScript
 * emits SVG path strings, this emits structured geometry (points, cubic
 * segments, capsule dimensions) that a Compose `Path` is built from. The
 * geometry itself — the sampling, the Catmull-Rom tangents, the ray casting — is
 * unchanged.
 */

class Pt(@JvmField var x: Double = 0.0, @JvmField var y: Double = 0.0)

/** One cubic segment of a closed contour, in absolute coordinates. */
class Cubic(
    @JvmField val c1x: Double,
    @JvmField val c1y: Double,
    @JvmField val c2x: Double,
    @JvmField val c2y: Double,
    @JvmField val x: Double,
    @JvmField val y: Double,
)

/** A closed contour: a start point plus the cubics that walk back round to it. */
class ClosedContour(
    @JvmField val startX: Double,
    @JvmField val startY: Double,
    @JvmField val cubics: List<Cubic>,
)

/**
 * A silhouette = a radial profile r(theta) plus a pose.
 *
 * Everything goes through profiles sampled at the SAME angles, so any two shapes
 * have points that correspond one to one and morphing reduces to a linear
 * interpolation of the radii. That is what makes the transitions clean without a
 * path-morphing library.
 */
class Silhouette(
    @JvmField var radii: DoubleArray,
    /** rotation of the profile, in radians */
    @JvmField var rot: Double = 0.0,
    /** offset of the centre, in ball-radius units */
    @JvmField var cx: Double = 0.0,
    @JvmField var cy: Double = 0.0,
    /** squash and stretch, applied in screen space (after rotation) */
    @JvmField var sx: Double = 1.0,
    @JvmField var sy: Double = 1.0,
) {
    fun copy(
        radii: DoubleArray = this.radii,
        rot: Double = this.rot,
        cx: Double = this.cx,
        cy: Double = this.cy,
        sx: Double = this.sx,
        sy: Double = this.sy,
    ) = Silhouette(radii, rot, cx, cy, sx, sy)
}

object BloubShape {

    val ANGLES: DoubleArray = DoubleArray(BloubProfiles.SAMPLES) { (it.toDouble() / BloubProfiles.SAMPLES) * TAU }
    val COS: DoubleArray = DoubleArray(BloubProfiles.SAMPLES) { cos(ANGLES[it]) }
    val SIN: DoubleArray = DoubleArray(BloubProfiles.SAMPLES) { sin(ANGLES[it]) }

    fun silhouette(name: String): Silhouette = Silhouette(BloubProfiles.byName(name).copyOf())

    /** A perfect circle: the neutral base (dot, bubble, cross-fade target). */
    fun circle(radius: Double): Silhouette =
        Silhouette(DoubleArray(BloubProfiles.SAMPLES) { radius })

    /** Interpolate two silhouettes. [out] is reused to avoid allocating at 60 fps. */
    fun blend(a: Silhouette, b: Silhouette, t: Double, out: Silhouette? = null): Silhouette {
        val dst = out ?: Silhouette(DoubleArray(BloubProfiles.SAMPLES))
        for (i in 0 until BloubProfiles.SAMPLES) {
            dst.radii[i] = lerp(a.radii.getOrElse(i) { 1.0 }, b.radii.getOrElse(i) { 1.0 }, t)
        }
        // Rotate the short way round: avoids a full turn going from +170 to -170 degrees.
        dst.rot = a.rot + shortestAngle(b.rot - a.rot) * t
        dst.cx = lerp(a.cx, b.cx, t)
        dst.cy = lerp(a.cy, b.cy, t)
        dst.sx = lerp(a.sx, b.sx, t)
        dst.sy = lerp(a.sy, b.sy, t)
        return dst
    }

    /** Project the silhouette to screen points. [scale] = ball radius in viewBox units. */
    fun toPoints(
        s: Silhouette,
        scale: Double,
        out: MutableList<Pt> = ArrayList(BloubProfiles.SAMPLES),
    ): MutableList<Pt> {
        val cr = cos(s.rot)
        val sr = sin(s.rot)
        while (out.size < BloubProfiles.SAMPLES) out.add(Pt())
        while (out.size > BloubProfiles.SAMPLES) out.removeAt(out.size - 1)
        for (i in 0 until BloubProfiles.SAMPLES) {
            val r = s.radii.getOrElse(i) { 1.0 }
            val x = r * COS[i]
            val y = r * SIN[i]
            // rotate, then squash in screen space, then translate
            val rx = x * cr - y * sr
            val ry = x * sr + y * cr
            val p = out[i]
            p.x = (rx * s.sx + s.cx) * scale
            p.y = (ry * s.sy + s.cy) * scale
        }
        return out
    }

    /**
     * Closed polyline to Catmull-Rom cubics.
     *
     * With 64 points the centred tangents are plenty: the contour is smooth to
     * the pixel even at 600 px.
     */
    fun closedContour(pts: List<Pt>, tension: Double = 1.0 / 6.0): ClosedContour? {
        val n = pts.size
        if (n < 3) return null
        val first = pts[0]
        val cubics = ArrayList<Cubic>(n)
        for (i in 0 until n) {
            val p0 = pts[(i - 1 + n) % n]
            val p1 = pts[i]
            val p2 = pts[(i + 1) % n]
            val p3 = pts[(i + 2) % n]
            cubics.add(
                Cubic(
                    p1.x + (p2.x - p0.x) * tension,
                    p1.y + (p2.y - p0.y) * tension,
                    p2.x - (p3.x - p1.x) * tension,
                    p2.y - (p3.y - p1.y) * tension,
                    p2.x,
                    p2.y,
                )
            )
        }
        return ClosedContour(first.x, first.y, cubics)
    }

    /**
     * Arbitrary polygon to radial profile, by ray casting from ([cx], [cy]).
     *
     * Builds the shapes that do not express naturally as r(theta) (the tapered
     * bar of the exclamation mark). Computed once at load, never in the render loop.
     */
    fun profileFromPolygon(poly: List<Pt>, cx: Double, cy: Double): DoubleArray {
        val radii = DoubleArray(BloubProfiles.SAMPLES)
        val n = poly.size
        for (k in 0 until BloubProfiles.SAMPLES) {
            val dx = COS[k]
            val dy = SIN[k]
            var best = 0.0
            for (i in 0 until n) {
                val a = poly[i]
                val b = poly[(i + 1) % n]
                val ex = b.x - a.x
                val ey = b.y - a.y
                val den = dx * ey - dy * ex
                if (abs(den) < 1e-9) continue
                val px = a.x - cx
                val py = a.y - cy
                val t = (px * ey - py * ex) / den // distance along the ray
                val u = (px * dy - py * dx) / den // position along the segment
                if (t > best && u >= 0 && u <= 1) best = t
            }
            radii[k] = best
        }
        return radii
    }

    /** Convex hull of two circles: the tapered bar of the upright exclamation mark. */
    fun hullOfCircles(
        x1: Double,
        y1: Double,
        r1: Double,
        x2: Double,
        y2: Double,
        r2v: Double,
        steps: Int = 96,
    ): List<Pt> {
        val dx = x2 - x1
        val dy = y2 - y1
        val dist = hypot(dx, dy).let { if (it == 0.0) 1e-6 else it }
        // angle of the common external tangents
        val base = atan2(dy, dx)
        val spread = acos(max(-1.0, min(1.0, (r1 - r2v) / dist)))
        val pts = ArrayList<Pt>(steps + 2)
        val half = steps / 2
        // arc of the larger circle
        for (i in 0..half) {
            val a = base + spread + ((TAU - 2 * spread) * i) / half
            pts.add(Pt(x1 + cos(a) * r1, y1 + sin(a) * r1))
        }
        // arc of the smaller circle
        for (i in 0..half) {
            val a = base - spread + ((2 * spread) * i) / half
            pts.add(Pt(x2 + cos(a) * r2v, y2 + sin(a) * r2v))
        }
        return pts
    }

    /**
     * Profile radius in an arbitrary direction, interpolated between the two
     * neighbouring samples.
     *
     * Re-seats whatever sits *on* the body (the eyes, the notification pip) when
     * the silhouette is no longer a circle: without it, an eye placed at 0.62
     * radius leaves a shape whose edge is at 0.55 in that direction, and the mask
     * clips it.
     */
    fun radiusAtAngle(radii: DoubleArray, angle: Double): Double {
        val n = radii.size
        val t = (((angle / TAU) % 1 + 1) % 1) * n
        val i = floor(t).toInt()
        return lerp(radii[i % n], radii[(i + 1) % n], t - i)
    }

    /** Superellipse: |x/sx|^n + |y/sy|^n = 1. n = 2 is an ellipse, n around 4 the squircle. */
    fun superellipseProfile(n: Double, sx: Double = 1.0, sy: Double = 1.0): DoubleArray =
        DoubleArray(BloubProfiles.SAMPLES) { i ->
            val c = abs(COS[i] / sx).pow(n)
            val s = abs(SIN[i] / sy).pow(n)
            (c + s).pow(-1.0 / n)
        }

    /**
     * Radial profile of the UNION of discs: r(theta) = the furthest of the
     * ray/circle intersections. Exact as long as the origin is inside the union —
     * that is what gives the cloud its lobes without a path boolean.
     */
    fun unionOfCirclesProfile(circles: List<Triple<Double, Double, Double>>): DoubleArray {
        val out = DoubleArray(BloubProfiles.SAMPLES)
        for (i in 0 until BloubProfiles.SAMPLES) {
            val dx = COS[i]
            val dy = SIN[i]
            var best = 0.0
            for ((cx, cy, r) in circles) {
                val b = dx * cx + dy * cy
                val disc = b * b - (cx * cx + cy * cy - r * r)
                if (disc < 0) continue
                val t = b + sqrt(disc)
                if (t > best) best = t
            }
            out[i] = best
        }
        return out
    }

    /**
     * Polygon with rounded corners, by Minkowski sum with a disc: each edge is
     * pushed out by [rc] and each vertex becomes an arc of radius [rc]. Vertices
     * are therefore to be placed at the wanted radius MINUS rc. Expects a
     * clockwise polygon (screen space, y down).
     */
    private fun roundedPolygon(verts: List<Pt>, rc: Double, arcSteps: Int = 10): List<Pt> {
        val n = verts.size
        val out = ArrayList<Pt>(n * (arcSteps + 1))
        fun normal(a: Pt, b: Pt): Double {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy).let { if (it == 0.0) 1.0 else it }
            // clockwise plus y down: the outward normal is (dy, -dx)
            return atan2(-dx / len, dy / len)
        }
        for (i in 0 until n) {
            val prev = verts[(i - 1 + n) % n]
            val cur = verts[i]
            val next = verts[(i + 1) % n]
            val a0 = normal(prev, cur)
            val a1 = normal(cur, next)
            val d = shortestAngle(a1 - a0)
            for (k in 0..arcSteps) {
                val a = a0 + (d * k) / arcSteps
                out.add(Pt(cur.x + cos(a) * rc, cur.y + sin(a) * rc))
            }
        }
        return out
    }

    /** Regular polygon with rounded corners, inscribed in [radius]. */
    fun regularPolygonProfile(
        sides: Int,
        radius: Double,
        rc: Double,
        rotationDeg: Double = 0.0,
    ): DoubleArray {
        val rot = degToRad(rotationDeg)
        val verts = List(sides) { i ->
            // clockwise on screen: theta grows with y pointing down
            val a = rot + (i.toDouble() / sides) * TAU
            Pt(cos(a) * (radius - rc), sin(a) * (radius - rc))
        }
        return profileFromPolygon(roundedPolygon(verts, rc), 0.0, 0.0)
    }
}

/**
 * A capsule (stadium) centred on the origin: the exact shape of the bot's eyes.
 *
 * The original emits an SVG path here; the dimensions are the same, and the
 * renderer turns them into a Compose round-rect.
 */
class Capsule(@JvmField val halfW: Double, @JvmField val halfH: Double, @JvmField val r: Double) {
    companion object {
        fun of(w: Double, h: Double): Capsule {
            val hw = max(w, 0.01) / 2
            val hh = max(h, 0.01) / 2
            return Capsule(hw, hh, min(hw, hh))
        }
    }
}
