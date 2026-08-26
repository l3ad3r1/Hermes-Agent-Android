package com.hermes.agent.data.tools

import com.hermes.agent.data.agent.ClarificationBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClarifyToolTest {

    @Test
    fun clarify_tool_round_trip_suspends_and_resumes_with_user_answer() = runTest(UnconfinedTestDispatcher()) {
        val bus = ClarificationBus()
        val tool = ClarifyTool(bus)
        val args = mapOf(
            "question" to JsonPrimitive("Do you prefer Tea or Coffee?"),
            "choices" to JsonArray(listOf(JsonPrimitive("Tea"), JsonPrimitive("Coffee"))),
        )
        var result: com.hermes.agent.domain.tool.ToolResult? = null
        val job = launch {
            result = tool.execute(args)
        }

        val req = bus.pending.value
        assertNotNull("Expected pending clarification request on the bus", req)
        assertEquals("Do you prefer Tea or Coffee?", req?.question)
        assertEquals(listOf("Tea", "Coffee"), req?.choices)

        bus.answer("Coffee")
        job.join()

        assertNotNull(result)
        assertTrue(result!!.success)
        assertEquals("Coffee", result!!.output)
    }

    @Test
    fun clarify_tool_requires_question_parameter() = runTest {
        val bus = ClarificationBus()
        val tool = ClarifyTool(bus)
        val result = tool.execute(emptyMap())
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("missing required parameter: question") == true)
    }
}
