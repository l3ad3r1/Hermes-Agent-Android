package com.hermes.agent.data.agent

import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.domain.agent.ExecutionStopReason
import com.hermes.agent.domain.agent.ToolExecutionObservation
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatedExecutionGuardTest {

    @Test
    fun `stops after three identical no-progress rounds`() {
        val session = RepeatedExecutionGuard().openSession()
        val observation = observation(
            arguments = mapOf("query" to JsonPrimitive("weather")),
            result = ToolResult.ok("unchanged"),
        )

        assertNull(session.observeRound(listOf(observation)))
        assertNull(session.observeRound(listOf(observation)))
        assertEquals(
            ExecutionStopReason.REPEATED_NO_PROGRESS,
            session.observeRound(listOf(observation)),
        )
    }

    @Test
    fun `changed result resets no-progress count`() {
        val session = RepeatedExecutionGuard().openSession()
        val first = observation(emptyMap(), ToolResult.ok("10 percent"))
        val second = observation(emptyMap(), ToolResult.ok("20 percent"))

        assertNull(session.observeRound(listOf(first)))
        assertNull(session.observeRound(listOf(first)))
        assertNull(session.observeRound(listOf(second)))
        assertNull(session.observeRound(listOf(second)))
    }

    @Test
    fun `canonical arguments ignore map and nested object key order`() {
        val left = mapOf(
            "z" to JsonPrimitive(1),
            "object" to JsonObject(
                linkedMapOf("b" to JsonPrimitive(2), "a" to JsonPrimitive(1)),
            ),
        )
        val right = linkedMapOf(
            "object" to JsonObject(
                linkedMapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2)),
            ),
            "z" to JsonPrimitive(1),
        )

        assertEquals(
            RepeatedExecutionGuard.canonicalArguments(left),
            RepeatedExecutionGuard.canonicalArguments(right),
        )
    }

    private fun observation(
        arguments: Map<String, kotlinx.serialization.json.JsonElement>,
        result: ToolResult,
    ) = ToolExecutionObservation(
        call = ToolCall("ignored-id", "web_search", arguments),
        result = result,
    )
}
