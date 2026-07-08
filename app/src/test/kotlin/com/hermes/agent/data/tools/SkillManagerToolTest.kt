package com.hermes.agent.data.tools

import com.hermes.agent.data.evolution.SkillRefineScheduler
import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.domain.tool.ToolRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillManagerToolTest {

    private val repo = mockk<SkillRepository>(relaxed = true)
    private val registry = mockk<ToolRegistry>(relaxed = true)
    private val scheduler = mockk<SkillRefineScheduler>(relaxed = true)
    private val tool = SkillManagerTool(repo, dagger.Lazy { registry }, scheduler)

    private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { it.first to JsonPrimitive(it.second) }

    private val validBody =
        "# Git Explainer\n## Purpose\nExplain any git command in two sentences with analogies.\n" +
            "## Steps\n1. Identify the command\n2. Explain with a non-technical analogy\n" +
            "## Example Trigger\n\"explain git rebase\""

    @Test
    fun `create persists a new skill with normalized name`() = runTest {
        coEvery { repo.getByName(any()) } returns null
        val contentSlot = slot<String>()
        coEvery {
            repo.upsert(any(), any(), capture(contentSlot), any(), any(), any(), any(), any())
        } returns mockk<Skill>(relaxed = true)

        val result = tool.execute(
            args(
                "action" to "create",
                "name" to "Git Explainer",
                "description" to "Explains git commands in two sentences",
                "content" to validBody,
                "tags" to "git,explainer",
            ),
        )

        assertTrue(result.success)
        coVerify {
            repo.upsert(
                name = "git-explainer", // kebab-cased
                description = any(), content = any(), category = any(),
                tags = any(), version = any(), requiresTools = any(), fallbackForTools = any(),
            )
        }
        // Content carries agentskills.io frontmatter.
        assertTrue(contentSlot.captured.startsWith("---"))
        assertTrue(contentSlot.captured.contains("name: git-explainer"))
    }

    @Test
    fun `create refuses to overwrite an existing skill`() = runTest {
        coEvery { repo.getByName("git-explainer") } returns mockk<Skill>(relaxed = true)

        val result = tool.execute(
            args(
                "action" to "create",
                "name" to "git-explainer",
                "description" to "dup",
                "content" to validBody,
            ),
        )

        assertFalse(result.success)
        coVerify(exactly = 0) {
            repo.upsert(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `create rejects stub content`() = runTest {
        coEvery { repo.getByName(any()) } returns null
        val result = tool.execute(
            args("action" to "create", "name" to "x", "description" to "d", "content" to "too short"),
        )
        assertFalse(result.success)
    }

    @Test
    fun `create requires name description and content`() = runTest {
        assertFalse(tool.execute(args("action" to "create")).success)
        assertFalse(tool.execute(args("action" to "create", "name" to "a")).success)
        assertFalse(
            tool.execute(args("action" to "create", "name" to "a", "description" to "b")).success,
        )
    }
}
