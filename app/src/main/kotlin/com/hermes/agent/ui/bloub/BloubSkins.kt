package com.hermes.agent.ui.bloub

import kotlin.math.cos

/**
 * Shapes and colours offered by the bot customiser. Faithful port of
 * `src/bot/skins.ts` from Bloub (github.com/jeremy-prt/bloub, MIT).
 *
 * Unlike the animation silhouettes ([BloubProfiles]), these are NOT measured off
 * the video: they are built analytically from the original customiser's grid.
 * Two distinct sources, and deliberately so — the animated states have to stay
 * faithful to the video, the base shapes are a user choice.
 */
enum class ShapeId(val id: String, val label: String) {
    CERCLE("cercle", "Circle"),
    GALET("galet", "Pebble"),
    SQUIRCLE("squircle", "Squircle"),
    CAPSULE("capsule", "Capsule"),
    TRIANGLE("triangle", "Triangle"),
    HEXAGONE("hexagone", "Hexagon"),
    NUAGE("nuage", "Cloud"),
    GOUTTE("goutte", "Droplet"),
    ;

    companion object {
        fun fromId(id: String?): ShapeId? = entries.firstOrNull { it.id == id }
    }
}

class BotShape(@JvmField val id: ShapeId, @JvmField val radii: DoubleArray)

/** Bring the maximum radius back to [max] so every shape weighs the same to the eye. */
private fun normalize(radii: DoubleArray, max: Double = 1.0): DoubleArray {
    val peak = radii.max()
    if (peak <= 0) return radii
    val k = max / peak
    return DoubleArray(radii.size) { radii[it] * k }
}

/** Pebble: a circle deformed by two low harmonics, so irregular but smooth. */
private val pebble = normalize(
    DoubleArray(BloubProfiles.SAMPLES) { i ->
        val a = BloubShape.ANGLES[i]
        1 + 0.075 * cos(2 * a + 0.5) + 0.035 * cos(3 * a + 2.1)
    },
    1.02,
)

/** Cloud: a union of bumps, wide at the bottom, two lobes on top. */
private val cloud = normalize(
    BloubShape.unionOfCirclesProfile(
        listOf(
            Triple(-0.44, 0.2, 0.54),
            Triple(0.46, 0.2, 0.5),
            Triple(0.02, 0.3, 0.6),
            Triple(-0.24, -0.3, 0.48),
            Triple(0.3, -0.24, 0.44),
        )
    ),
    1.02,
)

/** Droplet: a big disc at the bottom, a drawn-out point on top. */
private val droplet = normalize(
    BloubShape.profileFromPolygon(BloubShape.hullOfCircles(0.0, 0.28, 0.66, 0.0, -0.96, 0.05), 0.0, 0.0),
    1.04,
)

/** Capsule lying down: the hull of two discs side by side. */
private val capsule =
    BloubShape.profileFromPolygon(BloubShape.hullOfCircles(-0.42, 0.0, 0.62, 0.42, 0.0, 0.62), 0.0, 0.0)

val SHAPES: List<BotShape> = listOf(
    BotShape(ShapeId.CERCLE, DoubleArray(BloubProfiles.SAMPLES) { 1.0 }),
    BotShape(ShapeId.GALET, pebble),
    // 1.15 and not 1.02: on a superellipse the maximum radius is the diagonal, so
    // normalising on it gives a shape that looks smaller than the circle.
    BotShape(ShapeId.SQUIRCLE, normalize(BloubShape.superellipseProfile(4.2), 1.15)),
    BotShape(ShapeId.CAPSULE, capsule),
    // -90 degrees: one vertex towards the top of the screen (y points down)
    BotShape(ShapeId.TRIANGLE, BloubShape.regularPolygonProfile(3, 1.12, 0.34, -90.0)),
    // 0 degrees: vertices left and right, so flat top and bottom edges
    BotShape(ShapeId.HEXAGONE, BloubShape.regularPolygonProfile(6, 1.04, 0.26, 0.0)),
    BotShape(ShapeId.NUAGE, cloud),
    BotShape(ShapeId.GOUTTE, droplet),
)

val SHAPE_BY_ID: Map<ShapeId, BotShape> = SHAPES.associateBy { it.id }

val DEFAULT_SHAPE: ShapeId = ShapeId.CERCLE

enum class ColorId(val id: String, val label: String, val argb: Int) {
    ENCRE("encre", "Ink", 0xFF0A0A0C.toInt()),
    BRUN("brun", "Brown", 0xFF8B5E3C.toInt()),
    ROUGE("rouge", "Red", 0xFFE8483F.toInt()),
    ORANGE("orange", "Orange", 0xFFF08A24.toInt()),
    AMBRE("ambre", "Amber", 0xFFF0B429.toInt()),
    VERT("vert", "Green", 0xFF3ECF8E.toInt()),
    TURQUOISE("turquoise", "Turquoise", 0xFF2FBFA0.toInt()),
    BLEU("bleu", "Blue", 0xFF3B93F0.toInt()),
    VIOLET("violet", "Violet", 0xFF8B5CF6.toInt()),
    ROSE("rose", "Pink", 0xFFE152B0.toInt()),
    GRIS("gris", "Grey", 0xFFA3A3A3.toInt()),
    CREME("creme", "Cream", 0xFFF1EFE9.toInt()),
    ;

    companion object {
        fun fromId(id: String?): ColorId? = entries.firstOrNull { it.id == id }
    }
}

/** Palette of the original customiser, in the original's display order. */
val COLORS: List<ColorId> = listOf(
    ColorId.ENCRE,
    ColorId.BRUN,
    ColorId.ROUGE,
    ColorId.ORANGE,
    ColorId.AMBRE,
    ColorId.VERT,
    ColorId.TURQUOISE,
    ColorId.BLEU,
    ColorId.VIOLET,
    ColorId.ROSE,
    ColorId.GRIS,
    ColorId.CREME,
)

val DEFAULT_COLOR: ColorId = ColorId.ENCRE

/** Mix two packed ARGB colours. Used by the particles' depth haze. */
fun mixArgb(from: Int, to: Int, t: Double): Int {
    fun ch(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + (b - a) * t).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}
