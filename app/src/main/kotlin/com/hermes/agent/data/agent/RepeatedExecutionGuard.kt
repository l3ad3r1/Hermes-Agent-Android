package com.hermes.agent.data.agent

import com.hermes.agent.domain.agent.ExecutionGuard
import com.hermes.agent.domain.agent.ExecutionGuardSession
import com.hermes.agent.domain.agent.ExecutionStopReason
import com.hermes.agent.domain.agent.ToolExecutionObservation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Stops a model after it repeats the same completed tool round without progress. */
@Singleton
class RepeatedExecutionGuard @Inject constructor() : ExecutionGuard {
    override fun openSession(): ExecutionGuardSession = Session()

    private class Session : ExecutionGuardSession {
        private var previousRound: String? = null
        private var consecutiveMatches = 0

        override fun observeRound(
            observations: List<ToolExecutionObservation>,
        ): ExecutionStopReason? {
            if (observations.isEmpty()) {
                previousRound = null
                consecutiveMatches = 0
                return null
            }

            val fingerprint = observations.joinToString(separator = "\u001e") { observation ->
                buildString {
                    append(observation.call.name)
                    append('|')
                    append(canonicalArguments(observation.call.arguments))
                    append('|')
                    append(observation.result.success)
                    append('|')
                    append(observation.result.output)
                    append('|')
                    append(observation.result.errorMessage.orEmpty())
                }
            }

            consecutiveMatches = if (fingerprint == previousRound) consecutiveMatches + 1 else 1
            previousRound = fingerprint
            return if (consecutiveMatches >= MAX_IDENTICAL_ROUNDS) {
                ExecutionStopReason.REPEATED_NO_PROGRESS
            } else {
                null
            }
        }
    }

    companion object {
        const val MAX_IDENTICAL_ROUNDS = 3

        internal fun canonicalArguments(arguments: Map<String, JsonElement>): String =
            arguments.entries.sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                    "$key:${canonicalJson(value)}"
                }

        private fun canonicalJson(element: JsonElement): String = when (element) {
            is JsonObject -> element.entries.sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                    "$key:${canonicalJson(value)}"
                }
            is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
            is JsonPrimitive -> element.toString()
            JsonNull -> "null"
        }
    }
}
