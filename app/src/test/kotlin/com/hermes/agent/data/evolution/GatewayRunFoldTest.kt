package com.hermes.agent.data.evolution

import com.hermes.agent.data.plugin.evolution.BotRunResult
import com.hermes.agent.data.remote.GatewayEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRunFoldTest {

    private suspend fun fold(vararg events: GatewayEvent, max: Int = 1_000, approvals: MutableList<String> = mutableListOf()) =
        foldRunEvents(flowOf(*events), maxChars = max) { approvals += it.requestId }

    @Test
    fun `the complete reply wins over deltas and run output`() = runTest {
        val result = fold(
            GatewayEvent.MessageDelta("par"),
            GatewayEvent.MessageDelta("tial"),
            GatewayEvent.MessageComplete("final text"),
            GatewayEvent.RunCompleted("run output"),
        )
        assertEquals(BotRunResult.Completed("final text"), result)
    }

    @Test
    fun `falls back to run output, then to accumulated deltas`() = runTest {
        assertEquals(BotRunResult.Completed("out"), fold(GatewayEvent.MessageDelta("d"), GatewayEvent.RunCompleted("out")))
        assertEquals(BotRunResult.Completed("abc"), fold(GatewayEvent.MessageDelta("a"), GatewayEvent.MessageDelta("bc")))
    }

    @Test
    fun `a failed run, an empty run and an oversized reply are failures`() = runTest {
        assertTrue(fold(GatewayEvent.MessageComplete("x"), GatewayEvent.RunFailed("Connection lost")) is BotRunResult.Failed)
        assertTrue(fold(GatewayEvent.Unknown("ping", "{}")) is BotRunResult.Failed)
        assertTrue(fold(GatewayEvent.MessageComplete("x".repeat(2_000)), max = 1_000) is BotRunResult.Failed)
    }

    @Test
    fun `approval requests are handed to the caller, which denies them`() = runTest {
        val approvals = mutableListOf<String>()
        fold(
            GatewayEvent.ApprovalRequested("c1", "terminal", "{}", requestId = "r1"),
            GatewayEvent.MessageComplete("done"),
            approvals = approvals,
        )
        assertEquals(listOf("r1"), approvals)
    }
}
