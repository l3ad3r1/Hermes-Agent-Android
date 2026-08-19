package com.hermes.agent.domain.harness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptConstraintsTest {

    private val sensible =
        "Prefer bullet lists for multi-step answers. The user works in IST and " +
            "wants times stated in that zone."

    @Test
    fun `sensible guidance passes all gates`() {
        assertTrue(PromptConstraints.allPass(PromptConstraints.validate(sensible)))
    }

    @Test
    fun `stub guidance fails non_empty`() {
        val res = PromptConstraints.validate("be nice")
        assertFalse(res.first { it.name == "non_empty" }.passed)
    }

    @Test
    fun `oversized guidance fails size`() {
        val huge = "x".repeat(PromptConstraints.MAX_PROMPT_CHARS + 1)
        val res = PromptConstraints.validate(huge)
        assertFalse(res.first { it.name == "size" }.passed)
    }

    @Test
    fun `disproportionate growth fails growth`() {
        val baseline = "y".repeat(100)
        val grown = "y".repeat(400)
        val res = PromptConstraints.validate(grown, baseline = baseline)
        assertFalse(res.first { it.name == "growth" }.passed)
    }

    @Test
    fun `growth gate is skipped when there is no real baseline`() {
        // Going from "no notes at all" to a first real set is the intended
        // path, not runaway growth, so the ratio must not be applied there.
        val res = PromptConstraints.validate(sensible, baseline = "")
        assertTrue(res.none { it.name == "growth" })
        assertTrue(PromptConstraints.allPass(res))
    }

    @Test
    fun `modest growth from a real baseline passes`() {
        val baseline = "z".repeat(100)
        val res = PromptConstraints.validate("z".repeat(140), baseline = baseline)
        assertTrue(res.first { it.name == "growth" }.passed)
    }
}
