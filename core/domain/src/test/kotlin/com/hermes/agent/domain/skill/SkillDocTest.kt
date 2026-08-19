package com.hermes.agent.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frontmatter handling for SKILL.md documents.
 *
 * The description matters as much as the body: `SkillMatcher` scores it to
 * decide whether a skill is loaded at all, so a rewrite that corrupts the
 * frontmatter makes the skill unreachable rather than merely worse.
 */
class SkillDocTest {

    private val doc = """
        ---
        name: git-explainer
        description: Explains git commands in two sentences
        version: 1.0.0
        category: general
        tags: [git, explainer]
        author: user
        ---

        # Git Explainer

        ## Steps
        1. Identify the command.
    """.trimIndent()

    /** Built-in skills ship as plain markdown with no frontmatter at all. */
    private val bodyOnly = "# Research Skill\n\nUse this to research a topic."

    @Test
    fun `extractBody returns the markdown after the frontmatter`() {
        val body = SkillDoc.extractBody(doc)
        assertTrue(body.trimStart().startsWith("# Git Explainer"))
        assertFalse(body.contains("description:"))
    }

    @Test
    fun `frontmatterValue reads a scalar key`() {
        assertEquals("git-explainer", SkillDoc.frontmatterValue(doc, "name"))
        assertEquals(
            "Explains git commands in two sentences",
            SkillDoc.frontmatterValue(doc, "description"),
        )
    }

    @Test
    fun `frontmatterValue returns null for a body-only document`() {
        assertNull(SkillDoc.frontmatterValue(bodyOnly, "description"))
        assertFalse(SkillDoc.hasFrontmatter(bodyOnly))
        assertTrue(SkillDoc.hasFrontmatter(doc))
    }

    @Test
    fun `replaceDescription swaps the line and leaves everything else intact`() {
        val updated = SkillDoc.replaceDescription(doc, "Explains git commands, rebase and merge included")

        assertEquals(
            "Explains git commands, rebase and merge included",
            SkillDoc.frontmatterValue(updated, "description"),
        )
        // Neighbouring keys and the body survive untouched.
        assertEquals("git-explainer", SkillDoc.frontmatterValue(updated, "name"))
        assertEquals("1.0.0", SkillDoc.frontmatterValue(updated, "version"))
        assertEquals(SkillDoc.extractBody(doc), SkillDoc.extractBody(updated))
    }

    @Test
    fun `replaceDescription flattens a multi-line value onto one line`() {
        val updated = SkillDoc.replaceDescription(doc, "First line\nSecond line")

        // A raw newline would terminate the YAML scalar and split the
        // frontmatter, orphaning every key below it.
        assertEquals("First line Second line", SkillDoc.frontmatterValue(updated, "description"))
        assertEquals("1.0.0", SkillDoc.frontmatterValue(updated, "version"))
        assertEquals(SkillDoc.extractBody(doc), SkillDoc.extractBody(updated))
    }

    @Test
    fun `replaceDescription inserts the key when the frontmatter lacks it`() {
        val without = doc.replace("description: Explains git commands in two sentences\n", "")
        assertNull(SkillDoc.frontmatterValue(without, "description"))

        val updated = SkillDoc.replaceDescription(without, "Newly added description")
        assertEquals("Newly added description", SkillDoc.frontmatterValue(updated, "description"))
        assertEquals("git-explainer", SkillDoc.frontmatterValue(updated, "name"))
    }

    @Test
    fun `replaceDescription leaves a body-only document alone`() {
        assertEquals(bodyOnly, SkillDoc.replaceDescription(bodyOnly, "anything"))
    }

    @Test
    fun `replaceDescription ignores a blank value rather than emptying the key`() {
        assertEquals(doc, SkillDoc.replaceDescription(doc, "   "))
    }

    @Test
    fun `sanitizeDescription caps length`() {
        val long = "x".repeat(SkillDoc.MAX_DESCRIPTION_LENGTH + 50)
        assertEquals(SkillDoc.MAX_DESCRIPTION_LENGTH, SkillDoc.sanitizeDescription(long).length)
    }

    @Test
    fun `replaceBody and replaceDescription compose without clobbering each other`() {
        val newBody = "# Git Explainer\n\n## Steps\n1. Read the command.\n2. Explain it."
        val updated = SkillDoc.replaceDescription(
            SkillDoc.replaceBody(doc, newBody),
            "A sharper description",
        )

        assertEquals(newBody, SkillDoc.extractBody(updated))
        assertEquals("A sharper description", SkillDoc.frontmatterValue(updated, "description"))
        assertEquals("git-explainer", SkillDoc.frontmatterValue(updated, "name"))
    }

    @Test
    fun `bumpPatch increments only the patch component`() {
        assertEquals("1.2.4", SkillDoc.bumpPatch("1.2.3"))
        assertEquals("1.0.1", SkillDoc.bumpPatch("1.0.0"))
        // Non-semver strings are returned unchanged rather than mangled.
        assertEquals("draft", SkillDoc.bumpPatch("draft"))
    }
}
