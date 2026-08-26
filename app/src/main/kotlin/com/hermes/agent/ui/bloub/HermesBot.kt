package com.hermes.agent.ui.bloub

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.agent.ui.home.HermesPersona.Mood

/**
 * Hermes' own face: the bot engine wired to the persona.
 *
 * Screens use this rather than [BloubBot] directly, so the face is the same
 * everywhere and only one place knows how a mood becomes a state.
 *
 * **This build ships the face, not the customiser.** The body is always the
 * measured circle and the colour always follows the app's theme; [BloubBot]
 * itself still takes `shape`, `color` and `expression`, because the engine is
 * shared verbatim with Jeeves, where a settings screen drives all three. Keeping
 * the parameters here rather than hard-coding them inside the engine is what lets
 * the two stay one codebase — adding the customiser to Hermes later is adding a
 * screen, not editing the bot.
 */

/**
 * The state and expression each persona mood is drawn as.
 *
 * The mapping favours keeping a FACE on screen: the states that dissolve it
 * (`sleep` is a bouncing dot, `exclaim` a glyph) are left out, and the everyday
 * moods stay on `idle` — the only state that carries the resting face. The event
 * moods borrow the measured states that read at a glance.
 */
fun moodState(mood: Mood): StateId = when (mood) {
    Mood.NEUTRAL, Mood.HAPPY, Mood.FOCUSED, Mood.SLEEPY -> StateId.IDLE
    // the three dots, measured off the video: the clearest "working on it"
    Mood.THINKING -> StateId.THINKING
    // eyes wide open, the attentive state of the reference
    Mood.LISTENING -> StateId.WIDE
    // the body collapses and the particles spiral in — a real startle
    Mood.SURPRISED -> StateId.BURST
    Mood.CELEBRATE -> StateId.COMET
}

/**
 * The expression a mood carries.
 *
 * Only `idle` accepts one — the other states have an expression measured off the
 * video, and that is precisely what is being reproduced — so this is read only
 * where the mood genuinely differs from resting.
 */
fun moodExpression(mood: Mood, resting: ExpressionId = DEFAULT_EXPRESSION): ExpressionId = when (mood) {
    Mood.HAPPY -> ExpressionId.HEUREUX
    Mood.FOCUSED -> ExpressionId.ATTENTIF
    Mood.SLEEPY -> ExpressionId.SOMNOLENT
    else -> resting
}

/**
 * Hermes' face at a given mood.
 *
 * [size] is the side of the whole viewBox; the ball itself is about 0.63 of it,
 * the rest is the margin the orbit rings need.
 */
@Composable
fun HermesBot(
    mood: Mood,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
    aim: Offset? = null,
    /** overrides the mood's own expression; the greeting uses it to arrive excited */
    expression: ExpressionId? = null,
    /** play the arrival turn once on first appearance — see [BloubLook.tourLook] */
    arrival: Boolean = false,
    label: String? = "Hermes",
) {
    BloubBot(
        modifier = modifier,
        state = moodState(mood),
        size = size,
        shape = DEFAULT_SHAPE,
        expression = expression ?: moodExpression(mood),
        arrival = arrival,
        // Ink on paper in light mode, the reverse in dark — which is what the
        // app's monochrome scheme wants. `paper` is always the real background:
        // the eyes are holes, so what they show has to be what is behind them.
        ink = MaterialTheme.colorScheme.onBackground,
        paper = MaterialTheme.colorScheme.background,
        aim = aim,
        label = label,
    )
}
