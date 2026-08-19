package com.hermes.agent.ui.chat

import com.hermes.agent.ui.chat.components.HERMES_SLASH_COMMANDS
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
            assertTrue(cmd.template.isNotBlank())
        }
    }

    @Test
    fun testPlanCommandTemplate() {
        val planCmd = HERMES_SLASH_COMMANDS.find { it.command == "/plan" }
        assertNotNull(planCmd)
        assertEquals("ulw-plan ", planCmd!!.template)
    }

    @Test
    fun testResearchCommandTemplate() {
        val researchCmd = HERMES_SLASH_COMMANDS.find { it.command == "/research" }
        assertNotNull(researchCmd)
        assertEquals("ulw-research ", researchCmd!!.template)
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
}
