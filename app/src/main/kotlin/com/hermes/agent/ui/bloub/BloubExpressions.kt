package com.hermes.agent.ui.bloub

/**
 * The bot's resting expression. Faithful port of `src/bot/expressions.ts` from
 * Bloub (github.com/jeremy-prt/bloub, MIT).
 *
 * The face is only two capsules, so everything plays out on four levers: the
 * head's orientation, the eye separation, the eyes' proportions, and each eye's
 * own tilt. That last one is what makes anger and sadness possible: they need
 * MIRRORED tilts (tops converging or diverging), which head roll alone — it
 * tilts both eyes the same way — cannot do.
 *
 * Only the resting state carries this expression. The video's expressive states
 * (wink, wide eyes, notification) keep theirs: that is precisely what is being
 * reproduced.
 *
 * The [id]s are French because they are the original's — kept verbatim so the
 * ported numbers stay traceable to their source. [ExpressionId.label] carries
 * the English name shown in the UI.
 */
enum class ExpressionId(val id: String, val label: String) {
    NEUTRE("neutre", "Neutral"),
    ATTENTIF("attentif", "Attentive"),
    SURPRIS("surpris", "Surprised"),
    EXCITE("excite", "Excited"),
    HEUREUX("heureux", "Happy"),
    HILARE("hilare", "Delighted"),
    COLERE("colere", "Angry"),
    TRISTE("triste", "Sad"),
    EFFRAYE("effraye", "Afraid"),
    MEFIANT("mefiant", "Suspicious"),
    CONFUS("confus", "Confused"),
    CURIEUX("curieux", "Curious"),
    FIER("fier", "Proud"),
    TIMIDE("timide", "Shy"),
    BLASE("blase", "Unimpressed"),
    SOMNOLENT("somnolent", "Sleepy"),
    ;

    companion object {
        fun fromId(id: String?): ExpressionId? = entries.firstOrNull { it.id == id }
    }
}

class BotExpression(
    @JvmField val id: ExpressionId?,
    @JvmField val gaze: HeadGaze,
    @JvmField val split: Double,
    @JvmField val eyes: Array<EyeCfg>,
)

/** [tilt] in degrees, positive = the top of the capsule leans right. */
private fun eye(w: Double, h: Double, tilt: Double = 0.0, open: Double = 1.0): EyeCfg =
    EyeCfg(w, h, open, tilt)

/** Both eyes the same, tilts mirrored when [tilt] is given. */
private fun pair(w: Double, h: Double, tilt: Double = 0.0, open: Double = 1.0): Array<EyeCfg> =
    arrayOf(eye(w, h, tilt, open), eye(w, h, -tilt, open))

/**
 * The amplitudes follow bible-strong-avatar-lab, which exposes the same model
 * (head X/Y/Z, width and height per eye, separation, angle per eye): there the
 * width runs 0.8 to 2.7 times neutral, the height 0.3 to 1.5, and the angles up
 * to +-80 degrees. This stays inside that envelope.
 */
val EXPRESSIONS: List<BotExpression> = listOf(
    // the pose measured frame by frame on the reference video
    BotExpression(ExpressionId.NEUTRE, REST_GAZE, EYE_SPLIT, arrayOf(eye(EYE_W, EYE_H), eye(EYE_W, EYE_H))),
    BotExpression(ExpressionId.ATTENTIF, HeadGaze(4.0, 5.0, -4.0), 16.0, pair(0.21, 0.44)),
    BotExpression(ExpressionId.SURPRIS, HeadGaze(3.0, -3.0, 0.0), 19.0, pair(0.45, 0.47)),
    BotExpression(ExpressionId.EXCITE, HeadGaze(6.0, -14.0, 0.0), 19.5, pair(0.4, 0.56, -10.0)),
    // eyes narrowed into arcs: the tops converge slightly
    BotExpression(ExpressionId.HEUREUX, HeadGaze(5.0, 9.0, 0.0), 17.0, pair(0.27, 0.17, 14.0)),
    BotExpression(ExpressionId.HILARE, HeadGaze(4.0, 14.0, 0.0), 18.0, pair(0.34, 0.13, 20.0)),
    // eye tops converging hard towards the centre, plus narrowed eyes
    BotExpression(ExpressionId.COLERE, HeadGaze(3.0, 7.0, 0.0), 17.0, pair(0.34, 0.15, 30.0)),
    // the reverse: the tops diverge, and the gaze falls
    BotExpression(ExpressionId.TRISTE, HeadGaze(3.0, -13.0, 0.0), 16.0, pair(0.22, 0.4, -28.0)),
    BotExpression(ExpressionId.EFFRAYE, HeadGaze(2.0, -20.0, 0.0), 20.5, pair(0.4, 0.6)),
    // one eye distinctly more closed than the other
    BotExpression(ExpressionId.MEFIANT, HeadGaze(12.0, 6.0, -6.0), 16.0, arrayOf(eye(0.21, 0.4), eye(0.22, 0.15))),
    // asymmetric on both axes: mismatched sizes AND tilts. The narrowed eye is
    // deliberately flat (ratio 1.6): near a ratio of 1 it would read as round and
    // its tilt would not show.
    BotExpression(ExpressionId.CONFUS, HeadGaze(-14.0, 3.0, 8.0), 16.5, arrayOf(eye(0.2, 0.44, -18.0), eye(0.28, 0.17, 14.0))),
    // the head tips: it is the roll that carries the curiosity
    BotExpression(ExpressionId.CURIEUX, HeadGaze(16.0, -9.0, -15.0), 16.5, arrayOf(eye(0.24, 0.46, -8.0), eye(0.2, 0.38, -8.0))),
    BotExpression(ExpressionId.FIER, HeadGaze(5.0, 17.0, 0.0), 17.0, pair(0.3, 0.15, 18.0)),
    BotExpression(ExpressionId.TIMIDE, HeadGaze(-19.0, -14.0, -7.0), 14.0, pair(0.17, 0.3)),
    // horizontal slits and a gaze that wanders off to the side
    BotExpression(ExpressionId.BLASE, HeadGaze(-22.0, 2.0, 0.0), 16.0, pair(0.3, 0.12)),
    // half-dropped lids: this goes through `open`, so the vertical squash on
    // screen — the same mechanism as the blink
    BotExpression(ExpressionId.SOMNOLENT, HeadGaze(6.0, -9.0, -3.0), 16.0, pair(0.2, 0.42, 0.0, 0.42)),
)

val EXPRESSION_BY_ID: Map<ExpressionId, BotExpression> = EXPRESSIONS.associateBy { it.id!! }

val DEFAULT_EXPRESSION: ExpressionId = ExpressionId.NEUTRE

internal fun lerpEyeCfg(a: EyeCfg, b: EyeCfg, t: Double): EyeCfg = EyeCfg(
    w = lerp(a.w, b.w, t),
    h = lerp(a.h, b.h, t),
    open = lerp(a.open, b.open, t),
    tilt = lerp(a.tilt, b.tilt, t),
)

/** Interpolate two expressions: the change happens by gliding. */
fun blendExpression(a: BotExpression, b: BotExpression, t: Double): BotExpression = BotExpression(
    id = b.id,
    gaze = HeadGaze(
        yaw = lerp(a.gaze.yaw, b.gaze.yaw, t),
        pitch = lerp(a.gaze.pitch, b.gaze.pitch, t),
        roll = lerp(a.gaze.roll, b.gaze.roll, t),
    ),
    split = lerp(a.split, b.split, t),
    eyes = arrayOf(lerpEyeCfg(a.eyes[0], b.eyes[0], t), lerpEyeCfg(a.eyes[1], b.eyes[1], t)),
)
