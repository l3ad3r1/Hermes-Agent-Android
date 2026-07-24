package com.hermes.agent.data.agent

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmProvider
import com.hermes.agent.data.llm.LlmResponse
import com.hermes.agent.data.llm.LlmStreamChunk
import com.hermes.agent.data.llm.LlmToolResponse
import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.data.tool.ToolCallExecutor
import com.hermes.agent.data.tool.ToolRegistryImpl
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolExecutionPolicy
import com.hermes.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopRunnerTest {

    @Test
    fun `plain response completes without tools`() = runTest {
        val fixture = fixture { LlmToolResponse("done", emptyList(), 1, "fake", "stop") }

        val result = fixture.runner.run(
            fixture.provider,
            listOf(LlmMessage("user", "hello")),
            emptyList(),
            ExecutionOrigin.INTERACTIVE,
            { _, _ -> },
            null,
            { _, _ -> },
        )

        assertEquals(AgentLoopOutcome.Completed("done", emptyList()), result)
    }

    @Test
    fun `repeated identical tool round stops with actionable failure`() = runTest {
        val call = ToolCall("changing-id-is-ignored", "lookup", mapOf("q" to JsonPrimitive("same")))
        val fixture = fixture { LlmToolResponse("", listOf(call), 1, "fake", "tool_calls") }
        fixture.registry.register(stubTool("lookup"))
        coEvery { fixture.executor.execute(any(), confirmationGate = null) } returns ToolResult.ok("same result")

        val result = fixture.runWithTools()

        assertTrue(result is AgentLoopOutcome.Failed)
        result as AgentLoopOutcome.Failed
        assertEquals(AgentLoopFailureReason.REPEATED_NO_PROGRESS, result.reason)
        assertTrue(result.userMessage.contains("repeated without making progress"))
        coVerify(exactly = 3) { fixture.executor.execute(any(), confirmationGate = null) }
    }

    @Test
    fun `changing tool results count as progress and allow completion`() = runTest {
        val call = ToolCall("c", "lookup", emptyMap())
        val fixture = fixture { round ->
            if (round < 3) LlmToolResponse("", listOf(call), 1, "fake", "tool_calls")
            else LlmToolResponse("finished", emptyList(), 1, "fake", "stop")
        }
        fixture.registry.register(stubTool("lookup"))
        coEvery { fixture.executor.execute(any(), confirmationGate = null) } returnsMany listOf(
            ToolResult.ok("one"),
            ToolResult.ok("two"),
            ToolResult.ok("three"),
        )

        val result = fixture.runWithTools()

        assertEquals(AgentLoopOutcome.Completed("finished", listOf("lookup", "lookup", "lookup")), result)
    }

    @Test
    fun `headless confirmation denies execution and cannot hang`() = runTest {
        val call = ToolCall("c", "write", emptyMap())
        val fixture = fixture { LlmToolResponse("", listOf(call), 1, "fake", "tool_calls") }
        fixture.registry.register(stubTool("write", requiresConfirmation = true))

        val result = fixture.runWithTools(confirmationGate = null)

        assertTrue(result is AgentLoopOutcome.Failed)
        assertEquals(AgentLoopFailureReason.REPEATED_NO_PROGRESS, (result as AgentLoopOutcome.Failed).reason)
        coVerify(exactly = 0) { fixture.executor.execute(any(), any()) }
    }

    @Test
    fun `background origin denies never-autonomous tools outright`() = runTest {
        val call = ToolCall("c", "shell", emptyMap())
        val fixture = fixture { LlmToolResponse("", listOf(call), 1, "fake", "tool_calls") }
        fixture.registry.register(stubTool("shell"))
        val results = mutableListOf<ToolResult>()

        val result = fixture.runWithTools(
            origin = ExecutionOrigin.BACKGROUND,
            onToolResult = { _, r -> results += r },
        )

        assertTrue(result is AgentLoopOutcome.Failed)
        coVerify(exactly = 0) { fixture.executor.execute(any(), any()) }
        assertTrue(
            "denial must be actionable",
            results.first().errorMessage.orEmpty().contains("never allowed from background"),
        )
    }

    @Test
    fun `background origin denies confirmation-required tools without consulting the gate`() = runTest {
        val call = ToolCall("c", "write", emptyMap())
        val fixture = fixture { LlmToolResponse("", listOf(call), 1, "fake", "tool_calls") }
        fixture.registry.register(stubTool("write", requiresConfirmation = true))
        var gateConsulted = false
        val gate = ToolCallExecutor.ConfirmationGate { _, _ ->
            gateConsulted = true
            true
        }

        val result = fixture.runWithTools(
            origin = ExecutionOrigin.BACKGROUND,
            confirmationGate = gate,
        )

        assertTrue(result is AgentLoopOutcome.Failed)
        assertFalse("background turns must not wait on a confirmation gate", gateConsulted)
        coVerify(exactly = 0) { fixture.executor.execute(any(), any()) }
    }

    @Test
    fun `round limit reports a distinct failure`() = runTest {
        val fixture = fixture { round ->
            LlmToolResponse(
                "",
                listOf(ToolCall("c$round", "lookup", mapOf("round" to JsonPrimitive(round)))),
                1,
                "fake",
                "tool_calls",
            )
        }
        fixture.registry.register(stubTool("lookup"))
        coEvery { fixture.executor.execute(any(), confirmationGate = null) } returns ToolResult.ok("ok")

        val result = fixture.runWithTools()

        assertEquals(AgentLoopFailureReason.ROUND_LIMIT_REACHED, (result as AgentLoopOutcome.Failed).reason)
    }

    @Test
    fun `whole loop timeout cancels a stalled provider`() = runTest {
        val registry = ToolRegistryImpl()
        val executor = mockk<ToolCallExecutor>(relaxed = true)
        val provider = object : FakeProvider({ error("unused") }) {
            override suspend fun completeWithTools(
                messages: List<LlmMessage>,
                tools: List<ToolDescriptor>,
            ): LlmToolResponse {
                delay(AgentLoopRunner.MAX_LOOP_DURATION_MS + 1)
                return LlmToolResponse("late", emptyList(), 1, "fake", "stop")
            }
        }
        val runner = AgentLoopRunner(registry, executor, RepeatedExecutionGuard(), ToolExecutionPolicy())

        val result = runner.run(
            provider,
            emptyList(),
            emptyList(),
            ExecutionOrigin.INTERACTIVE,
            { _, _ -> },
            null,
            { _, _ -> },
        )

        assertEquals(AgentLoopFailureReason.TIMED_OUT, (result as AgentLoopOutcome.Failed).reason)
    }

    private fun fixture(response: (Int) -> LlmToolResponse): Fixture {
        val registry = ToolRegistryImpl()
        val executor = mockk<ToolCallExecutor>()
        return Fixture(
            registry,
            executor,
            FakeProvider(response),
            AgentLoopRunner(registry, executor, RepeatedExecutionGuard(), ToolExecutionPolicy()),
        )
    }

    private fun stubTool(name: String, requiresConfirmation: Boolean = false) = object : Tool {
        override val descriptor = ToolDescriptor(name, "test", emptyList(), requiresConfirmation = requiresConfirmation)
        override suspend fun execute(arguments: Map<String, kotlinx.serialization.json.JsonElement>) = ToolResult.ok("unused")
    }

    private data class Fixture(
        val registry: ToolRegistryImpl,
        val executor: ToolCallExecutor,
        val provider: LlmProvider,
        val runner: AgentLoopRunner,
    ) {
        suspend fun runWithTools(
            origin: ExecutionOrigin = ExecutionOrigin.INTERACTIVE,
            confirmationGate: ToolCallExecutor.ConfirmationGate? = null,
            onToolResult: suspend (ToolCall, ToolResult) -> Unit = { _, _ -> },
        ): AgentLoopOutcome = runner.run(
            provider,
            listOf(LlmMessage("user", "test")),
            registry.descriptors(),
            origin,
            { _, _ -> },
            confirmationGate,
            onToolResult,
        )
    }

    private open class FakeProvider(
        private val response: (Int) -> LlmToolResponse,
    ) : LlmProvider {
        private var round = 0
        override val name = "fake"
        override val isOnDevice = true
        override val model = "fake"
        override suspend fun complete(messages: List<LlmMessage>) = LlmResponse("", 0, model)
        override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> = flowOf(LlmStreamChunk.Done)
        override suspend fun completeWithTools(
            messages: List<LlmMessage>,
            tools: List<ToolDescriptor>,
        ): LlmToolResponse = response(round++)
        override suspend fun isAvailable() = true
    }
}
