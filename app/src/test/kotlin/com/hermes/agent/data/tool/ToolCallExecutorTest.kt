package com.hermes.agent.data.tool

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallExecutorTest {

    private class StubTool(
        override val descriptor: ToolDescriptor,
        val handler: suspend (Map<String, kotlinx.serialization.json.JsonElement>) -> ToolResult,
    ) : Tool {
        override suspend fun execute(arguments: Map<String, kotlinx.serialization.json.JsonElement>): ToolResult =
            handler(arguments)
    }

    private fun descriptor(name: String, requiresConfirmation: Boolean = false) = ToolDescriptor(
        name = name,
        description = "stub",
        parameters = listOf(
            ToolParameter("x", ToolParameterType.STRING, "x param"),
        ),
        requiresConfirmation = requiresConfirmation,
    )

    @Test
    fun `executes a registered tool and returns its result`() = runTest {
        val registry = ToolRegistryImpl()
        val tool = StubTool(descriptor("stub")) { args ->
            // Mirror production tools: unwrap the primitive's content rather
            // than interpolating the raw JsonElement (whose toString() keeps
            // the JSON quotes).
            val x = (args["x"] as? JsonPrimitive)?.content
            ToolResult.ok("got x=$x")
        }
        registry.register(tool)
        val executor = ToolCallExecutor(registry)

        val result = executor.execute(
            com.hermes.agent.data.llm.ToolCall(
                id = "c1",
                name = "stub",
                arguments = mapOf("x" to JsonPrimitive("hello")),
            )
        )
        assertTrue(result.success)
        assertEquals("got x=hello", result.output)
    }

    @Test
    fun `returns error for unknown tool name`() = runTest {
        val registry = ToolRegistryImpl()
        val executor = ToolCallExecutor(registry)

        val result = executor.execute(
            com.hermes.agent.data.llm.ToolCall(
                id = "c1",
                name = "nope",
                arguments = emptyMap(),
            )
        )
        assertTrue(!result.success)
        assertTrue(result.errorMessage!!.contains("unknown tool"))
    }

    @Test
    fun `surfaces thrown exception as ToolResult error`() = runTest {
        val registry = ToolRegistryImpl()
        registry.register(StubTool(descriptor("boom")) {
            throw RuntimeException("kaboom")
        })
        val executor = ToolCallExecutor(registry)

        val result = executor.execute(
            com.hermes.agent.data.llm.ToolCall(
                id = "c1",
                name = "boom",
                arguments = emptyMap(),
            )
        )
        assertTrue(!result.success)
        assertTrue(result.errorMessage!!.contains("kaboom"))
    }

    @Test
    fun `confirmation gate is invoked when tool requires confirmation`() = runTest {
        val registry = ToolRegistryImpl()
        var executed = false
        registry.register(StubTool(descriptor("guarded", requiresConfirmation = true)) {
            executed = true
            ToolResult.ok("ok")
        })
        val executor = ToolCallExecutor(registry)

        var gateCalled = false
        val gate = object : ToolCallExecutor.ConfirmationGate {
            override suspend fun confirm(
                call: com.hermes.agent.data.llm.ToolCall,
                requiresConfirmation: Boolean,
            ): Boolean {
                gateCalled = true
                return true
            }
        }

        val result = executor.execute(
            com.hermes.agent.data.llm.ToolCall(
                id = "c1",
                name = "guarded",
                arguments = emptyMap(),
            ),
            confirmationGate = gate,
        )
        assertTrue(gateCalled)
        assertTrue(result.success)
        assertTrue(executed)
    }

    @Test
    fun `confirmation gate returning false skips execution`() = runTest {
        val registry = ToolRegistryImpl()
        var executed = false
        registry.register(StubTool(descriptor("guarded", requiresConfirmation = true)) {
            executed = true
            ToolResult.ok("ok")
        })
        val executor = ToolCallExecutor(registry)

        val gate = object : ToolCallExecutor.ConfirmationGate {
            override suspend fun confirm(
                call: com.hermes.agent.data.llm.ToolCall,
                requiresConfirmation: Boolean,
            ): Boolean = false
        }

        val result = executor.execute(
            com.hermes.agent.data.llm.ToolCall(
                id = "c1",
                name = "guarded",
                arguments = emptyMap(),
            ),
            confirmationGate = gate,
        )
        assertTrue(!result.success)
        assertTrue(!executed)
        assertTrue(result.errorMessage!!.contains("declined"))
    }

    @Test
    fun `executeAll runs each call independently`() = runTest {
        val registry = ToolRegistryImpl()
        registry.register(StubTool(descriptor("a")) { ToolResult.ok("A") })
        registry.register(StubTool(descriptor("b")) { ToolResult.ok("B") })
        val executor = ToolCallExecutor(registry)

        val results = executor.executeAll(
            listOf(
                com.hermes.agent.data.llm.ToolCall("c1", "a", emptyMap()),
                com.hermes.agent.data.llm.ToolCall("c2", "b", emptyMap()),
                com.hermes.agent.data.llm.ToolCall("c3", "missing", emptyMap()),
            )
        )
        assertEquals(3, results.size)
        assertEquals("A", results[0].second.output)
        assertEquals("B", results[1].second.output)
        assertTrue(!results[2].second.success)
    }
}
