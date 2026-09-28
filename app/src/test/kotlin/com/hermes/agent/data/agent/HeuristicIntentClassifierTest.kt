package com.hermes.agent.data.agent

import com.hermes.agent.domain.model.AgentRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicIntentClassifierTest {

    private val classifier = HeuristicIntentClassifier()

    @Test
    fun `routes device-control prompts to DeviceControl`() = runTest {
        val result = classifier.route("please lower the screen brightness")
        assertTrue(result is com.hermes.agent.domain.agent.RoutingResult.Solo)
        assertEquals(AgentRole.DEVICE_CONTROL, (result as com.hermes.agent.domain.agent.RoutingResult.Solo).agent)
    }

    @Test
    fun `routes research prompts to Research`() = runTest {
        val result = classifier.route("search the web for latest AI news")
        assertTrue(result is com.hermes.agent.domain.agent.RoutingResult.Solo)
        assertEquals(AgentRole.RESEARCH, (result as com.hermes.agent.domain.agent.RoutingResult.Solo).agent)
    }

    @Test
    fun `searching the user's own files stays with Conversational, which has the file tools`() = runTest {
        for (prompt in listOf(
            "Using search_files in the workspace: which files mention eggs?",
            "find the file groceries.txt and summarise it",
            "search my notes.md for the word herbs",
        )) {
            val result = classifier.route(prompt)
            assertEquals(prompt, AgentRole.CONVERSATIONAL, (result as com.hermes.agent.domain.agent.RoutingResult.Solo).agent)
        }
    }

    @Test
    fun `routes productivity prompts to Productivity`() = runTest {
        val result = classifier.route("schedule a meeting for tomorrow at 3pm")
        assertTrue(result is com.hermes.agent.domain.agent.RoutingResult.Solo)
        assertEquals(AgentRole.PRODUCTIVITY, (result as com.hermes.agent.domain.agent.RoutingResult.Solo).agent)
    }

    @Test
    fun `routes creative prompts to Creative`() = runTest {
        val result = classifier.route("write a short poem about autumn")
        assertTrue(result is com.hermes.agent.domain.agent.RoutingResult.Solo)
        assertEquals(AgentRole.CREATIVE, (result as com.hermes.agent.domain.agent.RoutingResult.Solo).agent)
    }

    @Test
    fun `routes generic chit-chat to Conversational`() = runTest {
        val result = classifier.route("hi, how are you?")
        assertTrue(result is com.hermes.agent.domain.agent.RoutingResult.Solo)
        assertEquals(AgentRole.CONVERSATIONAL, (result as com.hermes.agent.domain.agent.RoutingResult.Solo).agent)
    }

    @Test
    fun `multi-agent pattern routes to Research then Creative`() = runTest {
        val result = classifier.route("search for recent EU AI Act updates then draft a summary memo")
        assertTrue(result is com.hermes.agent.domain.agent.RoutingResult.MultiAgent)
        val multi = result as com.hermes.agent.domain.agent.RoutingResult.MultiAgent
        assertEquals(listOf(AgentRole.RESEARCH, AgentRole.CREATIVE), multi.agents)
    }

    @Test
    fun `device-control wins over productivity for brightness change`() = runTest {
        // "schedule a brightness change" should go to DeviceControl, not Productivity.
        val result = classifier.route("schedule a brightness change for tonight")
        assertTrue(result is com.hermes.agent.domain.agent.RoutingResult.Solo)
        assertEquals(AgentRole.DEVICE_CONTROL, (result as com.hermes.agent.domain.agent.RoutingResult.Solo).agent)
    }
}
