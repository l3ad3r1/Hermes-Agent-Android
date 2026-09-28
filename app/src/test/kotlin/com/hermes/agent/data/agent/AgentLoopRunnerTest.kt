package com.hermes.agent.data.agent

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.LlmResponse
import com.hermes.agent.domain.llm.LlmStreamChunk
import com.hermes.agent.domain.llm.LlmToolResponse
import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.data.tool.ToolCallExecutor
import com.hermes.agent.data.tool.ToolRegistryImpl
import com.hermes.agent.data.tools.DeferredToolScope
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
import kotlinx.serialization.json.JsonObject
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
    fun `a recovered call repeated by the model is executed once`() = runTest {
        // On the S24 the local model could not read the tool result and reissued
        // the same heading-and-JSON call every round. Each one was executed, so
        // one "add a task" request wrote a duplicate row per round until the
        // loop hit its limit. A call rebuilt from that loose format is answered
        // from the first result instead of run again.
        val call = ToolCall(
            id = AgentLoopRunner.RECOVERED_CALL_PREFIX + "1",
            name = "todo",
            arguments = mapOf("action" to JsonPrimitive("create"), "title" to JsonPrimitive("Buy milk")),
        )
        val fixture = fixture { LlmToolResponse("", listOf(call), 1, "fake", "tool_calls") }
        fixture.registry.register(stubTool("todo"))
        coEvery { fixture.executor.execute(any(), confirmationGate = null) } returnsMany listOf(
            ToolResult.ok("Created task #1"),
            ToolResult.ok("Created task #2"),
            ToolResult.ok("Created task #3"),
        )

        fixture.runWithTools()

        coVerify(exactly = 1) { fixture.executor.execute(any(), confirmationGate = null) }
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
    fun `a declined tool ends the step instead of letting the model narrate`() = runTest {
        // K41: the refusal used to be fed back as one more tool observation, the loop
        // carried on, and the model answered "Here are all the entities currently
        // configured in your Home Assistant instance" for a call the user had refused.
        val call = ToolCall("c", "home_assistant", emptyMap())
        val fixture = fixture {
            LlmToolResponse("", listOf(call), 1, "fake", "tool_calls")
        }
        fixture.registry.register(stubTool("home_assistant", requiresConfirmation = true))
        val gate = ToolCallExecutor.ConfirmationGate { _, _ -> false }

        val result = fixture.runWithTools(confirmationGate = gate)

        assertTrue("a decline must end the step", result is AgentLoopOutcome.Failed)
        val failed = result as AgentLoopOutcome.Failed
        assertEquals(AgentLoopFailureReason.USER_DECLINED, failed.reason)
        assertTrue(
            "the user must be told what was declined: ${'$'}{failed.userMessage}",
            failed.userMessage.contains("home_assistant"),
        )
        assertTrue(
            "the user must be told nothing ran: ${'$'}{failed.userMessage}",
            failed.userMessage.contains("declined"),
        )
        // Nothing may execute after a refusal.
        coVerify(exactly = 0) { fixture.executor.execute(any(), any()) }
    }

    @Test
    fun `a declined tool still reports the refusal through onToolResult`() = runTest {
        val call = ToolCall("c", "write", emptyMap())
        val fixture = fixture { LlmToolResponse("", listOf(call), 1, "fake", "tool_calls") }
        fixture.registry.register(stubTool("write", requiresConfirmation = true))
        val seen = mutableListOf<Pair<String, ToolResult>>()

        fixture.runWithTools(
            confirmationGate = ToolCallExecutor.ConfirmationGate { _, _ -> false },
            onToolResult = { c, r -> seen += c.name to r },
        )

        // The ledger still records the refusal, so it stays auditable.
        assertEquals(1, seen.size)
        assertEquals("write", seen[0].first)
        assertFalse(seen[0].second.success)
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
        val runner = AgentLoopRunner(
            registry,
            executor,
            RepeatedExecutionGuard(),
            ToolExecutionPolicy(mockk(relaxed = true)),
        )

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

    @Test
    fun `a direct call to a granted deferred tool runs through the bridge`() = runTest {
        val call = ToolCall("c", "bookmarks", mapOf("action" to JsonPrimitive("list")))
        val fixture = fixture { round ->
            if (round == 0) LlmToolResponse("", listOf(call), 1, "fake", "tool_calls")
            else LlmToolResponse("done", emptyList(), 1, "fake", "stop")
        }
        fixture.registry.register(stubTool("tool_call", requiresConfirmation = true))
        fixture.registry.register(stubTool("bookmarks"))
        fixture.scope.publish(setOf("bookmarks"))
        val executed = mutableListOf<ToolCall>()
        coEvery { fixture.executor.execute(any(), confirmationGate = null) } answers {
            executed += firstArg<ToolCall>()
            ToolResult.ok("No bookmarks found.")
        }

        val result = fixture.run(listOf("tool_call"))

        assertEquals("done", (result as AgentLoopOutcome.Completed).reply)
        assertEquals("tool_call", executed.single().name)
        assertEquals(JsonPrimitive("bookmarks"), executed.single().arguments["tool_name"])
        assertEquals(JsonObject(call.arguments), executed.single().arguments["arguments"])
    }

    @Test
    fun `a bridged read-only tool does not ask while a bridged write still does`() = runTest {
        val read = bridged("bookmarks", "list")
        val write = bridged("scheduler", "create")
        val fixture = fixture { round ->
            when (round) {
                0 -> LlmToolResponse("", listOf(read), 1, "fake", "tool_calls")
                1 -> LlmToolResponse("", listOf(write), 1, "fake", "tool_calls")
                else -> LlmToolResponse("done", emptyList(), 1, "fake", "stop")
            }
        }
        fixture.registry.register(stubTool("tool_call", requiresConfirmation = true))
        fixture.registry.register(stubTool("bookmarks"))
        fixture.registry.register(stubTool("scheduler", requiresConfirmation = true))
        fixture.scope.publish(setOf("bookmarks", "scheduler"))
        coEvery { fixture.executor.execute(any(), confirmationGate = null) } returns ToolResult.ok("ok")
        val asked = mutableListOf<String>()
        val gate = ToolCallExecutor.ConfirmationGate { c, _ ->
            asked += (c.arguments["tool_name"] as JsonPrimitive).content
            true
        }

        fixture.run(listOf("tool_call"), confirmationGate = gate)

        assertEquals(listOf("scheduler"), asked)
    }

    @Test
    fun `a background run cannot reach a never-autonomous tool through the bridge`() = runTest {
        val call = bridged("alarm", "set")
        val fixture = fixture { LlmToolResponse("", listOf(call), 1, "fake", "tool_calls") }
        fixture.registry.register(stubTool("tool_call", requiresConfirmation = true))
        fixture.registry.register(stubTool("alarm"))
        fixture.scope.publish(setOf("alarm"))
        val results = mutableListOf<ToolResult>()

        fixture.run(listOf("tool_call"), origin = ExecutionOrigin.BACKGROUND, onToolResult = { _, r -> results += r })

        coVerify(exactly = 0) { fixture.executor.execute(any(), any()) }
        assertTrue(results.first().errorMessage.orEmpty().contains("never allowed from background"))
    }

    @Test
    fun `a tool outside the granted scope is still unauthorized`() = runTest {
        val call = ToolCall("c", "shell", emptyMap())
        val fixture = fixture { LlmToolResponse("", listOf(call), 1, "fake", "tool_calls") }
        fixture.registry.register(stubTool("tool_call", requiresConfirmation = true))
        fixture.registry.register(stubTool("shell"))
        fixture.scope.publish(setOf("bookmarks"))
        val results = mutableListOf<ToolResult>()

        fixture.run(listOf("tool_call"), onToolResult = { _, r -> results += r })

        coVerify(exactly = 0) { fixture.executor.execute(any(), any()) }
        assertEquals("unauthorized tool: shell", results.first().errorMessage)
    }

    private fun bridged(name: String, action: String) = ToolCall(
        "c-$name",
        "tool_call",
        mapOf(
            "tool_name" to JsonPrimitive(name),
            "arguments" to JsonObject(mapOf("action" to JsonPrimitive(action))),
        ),
    )

    private fun fixture(response: (Int) -> LlmToolResponse): Fixture {
        val registry = ToolRegistryImpl()
        val executor = mockk<ToolCallExecutor>()
        val scope = DeferredToolScope()
        return Fixture(
            registry,
            executor,
            FakeProvider(response),
            AgentLoopRunner(
                registry,
                executor,
                RepeatedExecutionGuard(),
                ToolExecutionPolicy(mockk(relaxed = true)),
                scope,
            ),
            scope,
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
        val scope: DeferredToolScope,
    ) {
        /** Runs with only [advertised] tools offered to the model, as the orchestrator does. */
        suspend fun run(
            advertised: List<String>,
            origin: ExecutionOrigin = ExecutionOrigin.INTERACTIVE,
            confirmationGate: ToolCallExecutor.ConfirmationGate? = null,
            onToolResult: suspend (ToolCall, ToolResult) -> Unit = { _, _ -> },
        ): AgentLoopOutcome = runner.run(
            provider,
            listOf(LlmMessage("user", "test")),
            registry.descriptors().filter { it.name in advertised },
            origin,
            { _, _ -> },
            confirmationGate,
            onToolResult,
        )

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
