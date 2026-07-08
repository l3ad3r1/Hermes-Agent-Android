package com.hermes.agent.data.evolution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceHeuristicsTest {

    @Test
    fun `detects common secret formats`() {
        assertTrue(TraceHeuristics.containsSecret("my key is sk-ant-api03-abcdefghijklmnop"))
        assertTrue(TraceHeuristics.containsSecret("token = abcdef0123456789xyz"))
        assertTrue(TraceHeuristics.containsSecret("ghp_0123456789abcdefABCDEF"))
    }

    @Test
    fun `ignores normal prose`() {
        assertFalse(TraceHeuristics.containsSecret("please summarize this arxiv paper for me"))
        assertFalse(TraceHeuristics.containsSecret("what is the weather today"))
    }

    @Test
    fun `matches skill by name`() {
        assertTrue(
            TraceHeuristics.isRelevantToSkill(
                "review this github pull request",
                skillName = "github-code-review",
                skillText = "Reviews GitHub pull requests for bugs.",
            ),
        )
    }

    @Test
    fun `matches skill by keyword overlap`() {
        assertTrue(
            TraceHeuristics.isRelevantToSkill(
                "can you fetch the latest arxiv preprint about transformers",
                skillName = "papers",
                skillText = "Fetch arxiv preprint metadata and summarize transformers research.",
            ),
        )
    }

    @Test
    fun `rejects unrelated message`() {
        assertFalse(
            TraceHeuristics.isRelevantToSkill(
                "book me a table for dinner tonight",
                skillName = "github-code-review",
                skillText = "Reviews GitHub pull requests for bugs and style issues.",
            ),
        )
    }
}
