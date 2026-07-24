package com.hermes.agent.data.tools

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmProvider
import com.hermes.agent.data.llm.LlmResponse
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.LlmStreamChunk
import com.hermes.agent.data.llm.LlmToolResponse
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.data.tool.ToolCallExecutor
import com.hermes.agent.domain.model.AgentTask
import com.hermes.agent.domain.repository.AgentTaskRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegateChildCapabilitiesTest {

    @Test
    fun `delegates receive only the reviewed read-only tools`() {
        setOf(
            "web_search",
            "web_fetch",
            "calculator",
            "get_current_datetime",
            "search_conversations",
        ).forEach { assertTrue(it, DelegateChildCapabilities.allows(it)) }
    }

    @Test
    fun `delegates cannot write schedule interact recurse or execute code`() {
        setOf(
            "create_note",
            "set_alarm",
            "notes",
            "memory",
            "todo",
            "scheduler",
            "calendar_add_event",
            "device_settings",
            "shell",
            "terminal",
            "termux",
            "clarify",
            "delegate",
            "notify",
            "generate_image",
            "speak",
        ).forEach { assertFalse(it, DelegateChildCapabilities.allows(it)) }
    }

    @Test
    fun `new globally registered tools default to denied`() {
        assertFalse(DelegateChildCapabilities.allows("future_write_tool"))
    }

    @Test
    fun `allowed child calls run through the standard executor`() = runTest {
        var round = 0
        val provider = object : LlmProvider {
            override val name = "test"
            override val isOnDevice = false
            override val model = "test"

            override suspend fun complete(messages: List<LlmMessage>) =
                LlmResponse("done", 0, model)

            override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> =
                flowOf(LlmStreamChunk.Done)

            override suspend fun completeWithTools(
                messages: List<LlmMessage>,
                tools: List<ToolDescriptor>,
            ): LlmToolResponse = if (round++ == 0) {
                LlmToolResponse(
                    content = "",
                    toolCalls = listOf(
                        ToolCall(
                            id = "child_call",
                            name = "calculator",
                            arguments = mapOf("expression" to JsonPrimitive("2+2")),
                        ),
                    ),
                    tokensUsed = 0,
                    model = model,
                    finishReason = "tool_calls",
                )
            } else {
                LlmToolResponse("The answer is 4.", emptyList(), 0, model, "stop")
            }

            override suspend fun isAvailable() = true
        }
        val router = mockk<LlmRouter>()
        coEvery { router.route(any()) } returns RoutingDecision.Ready(provider, "test")
        val calculator = mockk<Tool>()
        every { calculator.descriptor } returns ToolDescriptor(
            name = "calculator",
            description = "Calculate",
            parameters = emptyList(),
        )
        val registry = mockk<ToolRegistry>()
        every { registry.all() } returns listOf(calculator)
        val executor = mockk<ToolCallExecutor>()
        coEvery { executor.execute(any(), any()) } returns ToolResult.ok("4")
        val delegate = DelegateTool(
            router = router,
            toolRegistry = dagger.Lazy { registry },
            toolCallExecutor = dagger.Lazy { executor },
            agentTaskRepository = dagger.Lazy { mockk<AgentTaskRepository>(relaxed = true) },
        )

        val result = delegate.execute(mapOf("prompt" to JsonPrimitive("Calculate 2+2")))

        assertTrue(result.success)
        assertTrue(result.output.contains("The answer is 4."))
        coVerify(exactly = 1) {
            executor.execute(match { it.name == "calculator" }, any())
        }
    }

    @Test
    fun `background delegation queues tasks instead of running subagents`() = runTest {
        val router = mockk<com.hermes.agent.data.llm.LlmRouter>()
        val registry = mockk<ToolRegistry>()
        val executor = mockk<ToolCallExecutor>()
        val tasks = mockk<AgentTaskRepository>()
        coEvery { tasks.add(any(), any()) } answers {
            AgentTask(id = "t1", label = firstArg(), prompt = secondArg())
        }
        val delegate = DelegateTool(
            router = router,
            toolRegistry = dagger.Lazy { registry },
            toolCallExecutor = dagger.Lazy { executor },
            agentTaskRepository = dagger.Lazy { tasks },
        )

        val result = delegate.execute(
            mapOf(
                "prompt" to JsonPrimitive("Summarise the weekly report"),
                "background" to JsonPrimitive(true),
            ),
        )

        assertTrue(result.success)
        assertTrue(result.output.contains("Queued 1 background task"))
        coVerify(exactly = 1) { tasks.add(any(), "Summarise the weekly report") }
        // No subagent must run: the router is never consulted.
        coVerify(exactly = 0) { router.route(any()) }
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }
}
