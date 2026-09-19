package com.hermes.agent.tool

import com.hermes.agent.data.local.LocalBot
import com.hermes.agent.data.local.LocalBotStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageLocalBotsToolTest {

    private val existing = LocalBot(id = "localbot_1", name = "Scribe", systemPrompt = "p", createdAt = 0L)
    private val store = mockk<LocalBotStore>(relaxed = true) {
        every { bots } returns MutableStateFlow(listOf(existing))
    }
    private val tool = ManageLocalBotsTool(store)

    @Test
    fun `is not offered to any ordinary role`() {
        // AgentToolAccess grants by category or capability; neither may match a role grant.
        assertEquals("bot_management", tool.descriptor.category)
        assertEquals(setOf("bot_management"), tool.descriptor.capabilities)
    }

    @Test
    fun `create adds a bot`() = runTest {
        every { store.add("Chef", "Cook things", false) } returns existing.copy(name = "Chef")

        val result = tool.execute(mapOf("action" to JsonPrimitive("create"), "name" to JsonPrimitive("Chef"), "system_prompt" to JsonPrimitive("Cook things")))

        assertTrue(result.output, result.success)
        verify { store.add("Chef", "Cook things", false) }
    }

    @Test
    fun `create accepts title and description in place of name and system_prompt`() = runTest {
        every { store.add("Scribe", "You are Scribe. Your job: Take meeting notes", false) } returns existing.copy(name = "Scribe")

        val result = tool.execute(
            mapOf("action" to JsonPrimitive("create"), "title" to JsonPrimitive("Scribe"), "description" to JsonPrimitive("Take meeting notes")),
        )

        assertTrue(result.output, result.success)
        verify { store.add("Scribe", "You are Scribe. Your job: Take meeting notes", false) }
    }

    @Test
    fun `create without a prompt is refused`() = runTest {
        val result = tool.execute(mapOf("action" to JsonPrimitive("create"), "name" to JsonPrimitive("Chef")))

        assertFalse(result.success)
        verify(exactly = 0) { store.add(any(), any(), any()) }
    }

    @Test
    fun `remove matches the name case-insensitively`() = runTest {
        val result = tool.execute(mapOf("action" to JsonPrimitive("remove"), "name" to JsonPrimitive("scribe")))

        assertTrue(result.success)
        verify { store.remove("localbot_1") }
    }

    @Test
    fun `remove of an unknown bot changes nothing`() = runTest {
        val result = tool.execute(mapOf("action" to JsonPrimitive("remove"), "name" to JsonPrimitive("Ghost")))

        assertFalse(result.success)
        verify(exactly = 0) { store.remove(any()) }
    }
}
