package com.hermes.agent.ui.bots

import com.hermes.agent.ui.bloub.ExpressionId
import com.hermes.agent.ui.bloub.StateId
import com.hermes.agent.ui.bloub.moodExpression
import com.hermes.agent.ui.bloub.moodState
import com.hermes.agent.ui.home.HermesPersona

/** The face a bot's tab draws: which animation it is in, and the expression it wears while resting. */
internal data class BotFace(val state: StateId, val expression: ExpressionId)

/**
 * What the afternoon and evening have no strong feeling about. A bot picks one by its id and keeps
 * it, so a row of tabs is not a row of identical faces.
 */
private val EASYGOING = listOf(
    ExpressionId.NEUTRE,
    ExpressionId.ATTENTIF,
    ExpressionId.CURIEUX,
    ExpressionId.HEUREUX,
)

/**
 * A bot's face right now.
 *
 * The time of day sets the mood, through the same persona Home uses, so the two agree: bright in
 * the morning, drowsy at night. What the bot is doing then overrides it — composing a reply shows
 * the thinking dots — and a bot that cannot be reached looks it.
 *
 * [online] is null until the first availability check has finished; nothing is known then, so the
 * face stays neutral rather than guessing sad.
 */
internal fun botFace(hourOfDay: Int, botId: String, thinking: Boolean, online: Boolean?): BotFace {
    val mood = HermesPersona.compose(
        name = null,
        hourOfDay = hourOfDay,
        busyTask = null,
        isThinking = thinking,
    ).mood
    val resting = EASYGOING[Math.floorMod(botId.hashCode(), EASYGOING.size)]
    val expression = if (online == false) ExpressionId.TRISTE else moodExpression(mood, resting)
    return BotFace(moodState(mood), expression)
}
