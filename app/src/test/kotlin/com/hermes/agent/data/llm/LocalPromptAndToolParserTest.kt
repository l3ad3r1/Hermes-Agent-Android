package com.hermes.agent.data.llm

import com.hermes.agent.domain.tool.ToolDescriptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptAndToolParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `local prompt preserves recent user assistant and tool turns`() {
        val prompt = buildLocalPrompt(
            listOf(
                LlmMessage("system", "Be concise."),
                LlmMessage("user", "What time is it?"),
                LlmMessage("assistant", "I will check."),
                LlmMessage("tool", "10:30", toolCallId = "call_1"),
            ),
        )

        assertEquals("Be concise.", prompt.system)
        assertTrue(prompt.conversation.contains("User:\nWhat time is it?"))
        assertTrue(prompt.conversation.contains("Assistant:\nI will check."))
        assertTrue(prompt.conversation.contains("Tool result (call_1):\n10:30"))
        assertFalse(prompt.conversation.contains("<|begin_of_text|>"))
    }

    @Test
    fun `bounded local prompt keeps the newest turn`() {
        val prompt = buildLocalPrompt(
            messages = listOf(
                LlmMessage("user", "old".repeat(100)),
                LlmMessage("assistant", "middle".repeat(100)),
                LlmMessage("user", "newest request"),
            ),
            maxConversationChars = 80,
        )

        assertTrue(prompt.conversation.contains("newest request"))
        assertFalse(prompt.conversation.contains("oldold"))
    }

    @Test
    fun `shared text parser extracts object arguments and cleans content`() {
        val (content, calls) = extractTextToolCalls(
            "Checking. <tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"2+2\"}}</tool_call>",
            json,
        )

        assertEquals("Checking.", content)
        assertEquals(1, calls.size)
        assertEquals("calculator", calls.single().name)
        assertEquals("2+2", calls.single().arguments["expression"]?.jsonPrimitive?.content)
    }

    @Test
    fun `malformed tool envelope remains ordinary content`() {
        val raw = "<tool_call>not-json</tool_call>"
        val (content, calls) = extractTextToolCalls(raw, json)

        assertEquals(raw, content)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `local provider advertises tools and returns parsed tool calls`() = runTest {
        val manager = mockk<LocalLlmManager>()
        every { manager.generateResponse(any(), any()) } returns flowOf(
            "<tool_call>{\"name\":\"calculator\",\"arguments\":{\"expression\":\"2+2\"}}</tool_call>",
        )
        val provider = LocalLlmProvider(manager, json)

        val response = provider.completeWithTools(
            messages = listOf(LlmMessage("user", "Calculate 2+2")),
            tools = listOf(
                ToolDescriptor(
                    name = "calculator",
                    description = "Evaluate arithmetic.",
                    parameters = emptyList(),
                ),
            ),
        )

        assertEquals("tool_calls", response.finishReason)
        assertEquals("calculator", response.toolCalls.single().name)
        assertEquals("2+2", response.toolCalls.single().arguments["expression"]?.jsonPrimitive?.content)
        verify(exactly = 1) {
            manager.generateResponse(
                match { it.contains("calculator") && it.contains("<tool_call>") },
                match { it.contains("Calculate 2+2") },
            )
        }
    }
}
