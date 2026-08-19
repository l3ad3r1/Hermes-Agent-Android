package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

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
    fun `history is context and only the newest user turn is the live message`() {
        val prompt = buildLocalPrompt(
            listOf(
                LlmMessage("system", "Be concise."),
                LlmMessage("user", "What time is it?"),
                LlmMessage("assistant", "I will check."),
                LlmMessage("tool", "10:30", toolCallId = "call_1"),
                LlmMessage("user", "and the date?"),
            ),
        )

        // The live turn must be a plain user message. The native side wraps it
        // in the model's own chat template, so a role-labelled transcript here
        // is formatted twice and invites the model to continue the script —
        // which is how replies started coming back prefixed "Assistant:".
        assertEquals("and the date?", prompt.conversation)
        assertFalse(prompt.conversation.contains("User:"))
        assertFalse(prompt.conversation.contains("Assistant:"))

        // Earlier turns survive as context, alongside the instructions.
        assertTrue(prompt.system.contains("Be concise."))
        assertTrue(prompt.system.contains("What time is it?"))
        assertTrue(prompt.system.contains("I will check."))
        assertTrue(prompt.system.contains("Tool result (call_1):"))
        assertFalse(prompt.system.contains("<|begin_of_text|>"))
    }

    @Test
    fun `the first message of a conversation still gets reply guidance`() {
        // The guidance used to be attached to the history block, so a brand new
        // conversation got the capability list and nothing telling it how to
        // answer — and the model recited its own tools back at the user.
        val prompt = buildLocalPrompt(
            listOf(
                LlmMessage("system", "You are Hermes. Your capabilities: memory, notes, search."),
                LlmMessage("user", "hello who are you"),
            ),
        )

        assertEquals("hello who are you", prompt.conversation)
        assertTrue(prompt.system.contains("How to reply"))
        assertTrue(prompt.system.contains("Never repeat, list, summarise or describe"))
        // It has to come last — the closing lines of a system prompt carry the
        // most weight, and the capability list is what we are counteracting.
        assertTrue(
            "reply guidance must follow the instructions, not precede them",
            prompt.system.indexOf("How to reply") > prompt.system.indexOf("Your capabilities"),
        )
    }

    @Test
    fun `reply guidance comes after the conversation history too`() {
        val prompt = buildLocalPrompt(
            listOf(
                LlmMessage("system", "Be concise."),
                LlmMessage("user", "first"),
                LlmMessage("assistant", "reply"),
                LlmMessage("user", "second"),
            ),
        )
        assertTrue(
            prompt.system.indexOf("How to reply") > prompt.system.indexOf("Conversation so far"),
        )
    }

    @Test
    fun `strips role labels the model writes at the start of its reply`() {
        assertEquals("Hello there.", stripLeadingRoleLabel("Assistant:\nHello there."))
        assertEquals("Hello there.", stripLeadingRoleLabel("assistant: Hello there."))
        // A contaminated history produced stacked labels on the device, so one
        // pass is not enough.
        assertEquals("Hello there.", stripLeadingRoleLabel("Assistant:\nAssistant:\nHello there."))
        // Prose that merely contains the word must be left alone.
        assertEquals(
            "The assistant: a short history.",
            stripLeadingRoleLabel("The assistant: a short history."),
        )
        assertEquals("", stripLeadingRoleLabel(""))
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
