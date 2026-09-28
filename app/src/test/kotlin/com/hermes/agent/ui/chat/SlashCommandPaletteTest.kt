package com.hermes.agent.ui.chat

import com.hermes.agent.ui.chat.components.HERMES_SLASH_COMMANDS
import com.hermes.agent.ui.chat.components.SlashAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandPaletteTest {

    @Test
    fun testAllCommandsHaveValidSyntaxAndTemplates() {
        assertTrue(HERMES_SLASH_COMMANDS.isNotEmpty())
        for (cmd in HERMES_SLASH_COMMANDS) {
            assertTrue(cmd.command.startsWith("/"))
            assertTrue(cmd.syntax.startsWith("/"))
            assertTrue(cmd.title.isNotBlank())
            assertTrue(cmd.description.isNotBlank())
            // Every command does something: it prefills a prompt, or the app carries it out.
            assertTrue(cmd.command, cmd.template.isNotBlank() || cmd.action != null)
        }
    }

    @Test
    fun testPlanCommandTemplate() {
        val planCmd = HERMES_SLASH_COMMANDS.find { it.command == "/plan" }
        assertNotNull(planCmd)
        assertEquals("Make a step-by-step plan for: ", planCmd!!.template)
    }

    @Test
    fun testResearchCommandTemplate() {
        val researchCmd = HERMES_SLASH_COMMANDS.find { it.command == "/research" }
        assertNotNull(researchCmd)
        assertEquals("Research this thoroughly and cite sources: ", researchCmd!!.template)
    }

    @Test
    fun testModelRoutingCommands() {
        val ultrabrainCmd = HERMES_SLASH_COMMANDS.find { it.command == "/model ultrabrain" }
        assertNotNull(ultrabrainCmd)
        assertEquals("[ultrabrain] ", ultrabrainCmd!!.template)

        val quickCmd = HERMES_SLASH_COMMANDS.find { it.command == "/model quick" }
        assertNotNull(quickCmd)
        assertEquals("[quick] ", quickCmd!!.template)
    }

    @Test
    fun exportIsAnAppActionNotAPrompt() {
        // It used to prefill "Export this conversation trajectory" for a model with no export tool.
        val export = HERMES_SLASH_COMMANDS.single { it.command == "/export" }
        assertEquals(SlashAction.EXPORT, export.action)
    }

    @Test
    fun delegateAsksForABackgroundTask() {
        val delegate = HERMES_SLASH_COMMANDS.single { it.command == "/delegate" }
        assertTrue(delegate.template.contains("background=true"))
    }
}
