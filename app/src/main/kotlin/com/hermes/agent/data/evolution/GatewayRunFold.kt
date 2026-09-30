package com.hermes.agent.data.evolution

import com.hermes.agent.data.plugin.evolution.BotRunResult
import com.hermes.agent.data.remote.GatewayEvent
import kotlinx.coroutines.flow.Flow

/**
 * Folds a run's event stream into its final text. The last complete reply wins,
 * then the run's own output, then the accumulated deltas; a failed run or a
 * stream that ends with no text is a failure. Output beyond [maxChars] is a
 * failure too — the parsers would refuse it anyway, and it is not worth holding.
 */
internal suspend fun foldRunEvents(
    events: Flow<GatewayEvent>,
    maxChars: Int = 256 * 1024,
    onApproval: suspend (GatewayEvent.ApprovalRequested) -> Unit,
): BotRunResult {
    val deltas = StringBuilder()
    var complete: String? = null
    var failure: String? = null
    var finished: String? = null
    events.collect { event ->
        when (event) {
            is GatewayEvent.MessageDelta -> if (deltas.length <= maxChars) deltas.append(event.text)
            is GatewayEvent.MessageComplete -> complete = event.text
            is GatewayEvent.RunCompleted -> finished = event.output
            is GatewayEvent.RunFailed -> failure = event.message
            is GatewayEvent.ApprovalRequested -> onApproval(event)
            else -> Unit
        }
    }
    failure?.let { return BotRunResult.Failed(it) }
    val text = listOf(complete, finished, deltas.toString()).firstOrNull { !it.isNullOrBlank() }
        ?: return BotRunResult.Failed("the bot finished without a reply")
    if (text.length > maxChars) return BotRunResult.Failed("the bot's reply was larger than $maxChars characters")
    return BotRunResult.Completed(text)
}
