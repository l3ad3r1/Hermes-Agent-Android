package com.hermes.agent.domain.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillConstraintsTest {

    private fun result(body: String, baseline: String? = null) =
        SkillConstraints.validate(body, baseline)

    @Test
    fun `well-formed body passes all gates`() {
        val body = "# Purpose\nDo the thing.\n\n## Steps\n1. First\n2. Second"
        assertTrue(SkillConstraints.allPass(result(body)))
    }

    @Test
    fun `empty or stub body fails non_empty`() {
        val res = result("# x")
        assertFalse(SkillConstraints.allPass(res))
        assertFalse(res.first { it.name == "non_empty" }.passed)
    }

    @Test
    fun `oversized body fails size gate`() {
        val huge = "# H\n" + "a".repeat(SkillConstraints.MAX_SKILL_BYTES + 1)
        val res = result(huge)
        assertFalse(res.first { it.name == "size" }.passed)
    }

    @Test
    fun `disproportionate growth fails growth gate`() {
        val baseline = "# H\n" + "a".repeat(100)
        val grown = "# H\n" + "a".repeat(400) // 4x
        val res = result(grown, baseline)
        assertFalse(res.first { it.name == "growth" }.passed)
    }

    @Test
    fun `body without heading fails structure gate`() {
        val body = "Just some prose with no markdown heading at all, long enough to pass length."
        val res = result(body)
        assertFalse(res.first { it.name == "structure" }.passed)
    }
}
