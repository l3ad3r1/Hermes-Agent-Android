package com.hermes.agent.domain.skill

import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.model.SkillLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Semantics ported from hermes-agent's `_skill_should_show`
 * (agent/prompt_builder.py):
 *  - requiresTools hides the skill when ANY required tool is missing.
 *  - fallbackForTools hides the skill when ANY listed tool IS present.
 *  - ARCHIVED skills are never visible.
 */
class SkillActivationTest {

    private fun skill(
        requires: List<String> = emptyList(),
        fallbackFor: List<String> = emptyList(),
        lifecycle: SkillLifecycle = SkillLifecycle.ACTIVE,
    ) = Skill(
        id = "s1",
        name = "test-skill",
        description = "d",
        content = "c",
        requiresTools = requires,
        fallbackForTools = fallbackFor,
        lifecycleState = lifecycle,
    )

    @Test
    fun `unconditional skill is always visible`() {
        assertTrue(SkillActivation.isVisible(skill(), emptySet()))
        assertTrue(SkillActivation.isVisible(skill(), setOf("web_search")))
    }

    @Test
    fun `requires hides skill when a required tool is missing`() {
        val s = skill(requires = listOf("web_search", "web_fetch"))
        assertFalse(SkillActivation.isVisible(s, setOf("web_search")))
        assertFalse(SkillActivation.isVisible(s, emptySet()))
    }

    @Test
    fun `requires shows skill when all required tools are present`() {
        val s = skill(requires = listOf("web_search", "web_fetch"))
        assertTrue(SkillActivation.isVisible(s, setOf("web_search", "web_fetch", "shell")))
    }

    @Test
    fun `fallback skill is hidden while the primary tool is available`() {
        val s = skill(fallbackFor = listOf("calendar_add_event"))
        assertFalse(SkillActivation.isVisible(s, setOf("calendar_add_event", "memory")))
    }

    @Test
    fun `fallback skill shows when the primary tool is missing`() {
        val s = skill(fallbackFor = listOf("calendar_add_event"))
        assertTrue(SkillActivation.isVisible(s, setOf("memory", "notes")))
    }

    @Test
    fun `archived skill is never visible even if gates pass`() {
        val s = skill(lifecycle = SkillLifecycle.ARCHIVED)
        assertFalse(SkillActivation.isVisible(s, setOf("web_search")))
    }

    @Test
    fun `stale skill remains visible`() {
        val s = skill(lifecycle = SkillLifecycle.STALE)
        assertTrue(SkillActivation.isVisible(s, emptySet()))
    }

    @Test
    fun `requires and fallback combine - fallback wins when primary present`() {
        val s = skill(requires = listOf("web_search"), fallbackFor = listOf("calendar_add_event"))
        // Primary present → hidden despite requires being satisfied.
        assertFalse(SkillActivation.isVisible(s, setOf("web_search", "calendar_add_event")))
        // Primary absent + requires satisfied → visible.
        assertTrue(SkillActivation.isVisible(s, setOf("web_search")))
    }

    @Test
    fun `partition splits visible and hidden`() {
        val visible = skill()
        val gated = skill(requires = listOf("nonexistent_tool"))
        val (v, h) = SkillActivation.partition(listOf(visible, gated), setOf("web_search"))
        assertEquals(listOf(visible), v)
        assertEquals(listOf(gated), h)
    }
}
