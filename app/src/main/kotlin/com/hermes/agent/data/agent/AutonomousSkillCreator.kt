package com.hermes.agent.data.agent

import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.domain.skill.SkillGuard
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Autonomous skill creation — second half of the closed learning loop.
 *
 * After any orchestrator run that used 2+ distinct tool types, this class
 * analyses the task and generates a reusable skill document in the
 * agentskills.io-compatible format, then saves it to [SkillRepository].
 *
 * Trigger threshold: 2 or more distinct tool names (excluding
 * memory/notes/datetime which are utility calls, not task-defining ones).
 *
 * The generated skill content follows the agentskills.io open standard:
 * YAML frontmatter (name, description, version, tags) + markdown body
 * (Purpose, Steps, Tools Used, Example Trigger).
 *
 * De-duplication: if a skill with the same kebab-case name already exists
 * it is not overwritten — the [SkillImprovementWorker] handles evolution.
 */
@Singleton
class AutonomousSkillCreator @Inject constructor(
    private val llmProvider: CloudLlmProvider,
    private val skillRepository: SkillRepository,
    private val dispatchers: DispatcherProvider,
) {

    private val SKIP_TOOLS = setOf("memory", "notes", "get_current_datetime", "calculator")

    suspend fun maybeCreateSkill(
        userRequest: String,
        agentSummary: String,
        toolsUsed: List<String>,
    ) = withContext(dispatchers.io) {
        if (!llmProvider.isAvailable()) return@withContext

        val taskTools = toolsUsed.toSet() - SKIP_TOOLS
        if (taskTools.size < 2) return@withContext

        val prompt = buildString {
            appendLine("User request: ${userRequest.take(400)}")
            appendLine("Agent completed: ${agentSummary.take(400)}")
            appendLine("Tools used: ${taskTools.joinToString(", ")}")
        }

        val response = runCatching {
            llmProvider.complete(
                listOf(
                    LlmMessage(role = "system", content = SKILL_GEN_SYSTEM),
                    LlmMessage(role = "user", content = prompt),
                )
            )
        }.onFailure { Timber.tag("SkillCreator").w(it, "generation failed") }
            .getOrNull() ?: return@withContext

        val parsed = parseSkillResponse(response.content) ?: return@withContext

        // Skip if a skill with this name already exists.
        if (skillRepository.getByName(parsed.name) != null) {
            Timber.tag("SkillCreator").d("skill '${parsed.name}' already exists, skipping")
            return@withContext
        }

        // Skills Guard: never persist generated content that carries
        // injection/exfiltration/destructive instructions.
        val verdict = SkillGuard.vet(parsed.content)
        if (!verdict.ok) {
            Timber.tag("SkillCreator").w(
                "skill '${parsed.name}' rejected by Skills Guard: ${verdict.flags.joinToString()}",
            )
            return@withContext
        }

        runCatching {
            skillRepository.upsert(
                name = parsed.name,
                description = parsed.description,
                content = parsed.content,
                category = parsed.category,
                tags = parsed.tags,
                version = "1.0.0",
                // Self-gating: the skill was distilled from a run that used
                // these tools, so it only makes sense where they exist —
                // conditional activation hides it if one goes away.
                requiresTools = taskTools.sorted(),
            )
        }.onSuccess { Timber.tag("SkillCreator").i("auto-created skill: ${parsed.name}") }
         .onFailure { Timber.tag("SkillCreator").w(it, "save failed for ${parsed.name}") }
    }

    private data class ParsedSkill(
        val name: String,
        val description: String,
        val category: String,
        val tags: List<String>,
        val content: String,
    )

    private fun parseSkillResponse(raw: String): ParsedSkill? {
        // Expected format:
        // SKILL_NAME: some-kebab-name
        // SKILL_DESCRIPTION: one line description
        // SKILL_CATEGORY: research|productivity|automation|devops|general
        // SKILL_TAGS: tag1,tag2,tag3
        // ---
        // {markdown body}
        val lines = raw.trim().lines()
        val meta = mutableMapOf<String, String>()
        var bodyStart = 0
        for ((i, line) in lines.withIndex()) {
            if (line.startsWith("---")) { bodyStart = i + 1; break }
            val colon = line.indexOf(':')
            if (colon > 0) meta[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }

        val name = meta["SKILL_NAME"]
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), "-")
            ?.trim('-')
            ?.take(50)
            ?: return null

        val description = meta["SKILL_DESCRIPTION"]?.take(200) ?: return null
        val category = meta["SKILL_CATEGORY"]?.lowercase()
            ?.let { if (it in VALID_CATEGORIES) it else "general" } ?: "general"
        val tags = meta["SKILL_TAGS"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList()
        val body = if (bodyStart < lines.size) lines.drop(bodyStart).joinToString("\n").trim() else ""

        if (name.isBlank() || description.isBlank() || body.length < 50) return null

        // Build full agentskills.io-compatible document.
        val content = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: $description")
            appendLine("version: 1.0.0")
            appendLine("category: $category")
            appendLine("tags: [${tags.joinToString(", ")}]")
            appendLine("author: hermes-auto")
            appendLine("---")
            appendLine()
            appendLine(body)
        }

        return ParsedSkill(name, description, category, tags, content)
    }

    companion object {
        private val VALID_CATEGORIES =
            setOf("research", "productivity", "automation", "devops", "general", "software-development")

        private val SKILL_GEN_SYSTEM = """
            You are a skill-authoring assistant for the Jeeves AI agent.
            When given a completed task, generate a reusable skill document so similar
            tasks can be done faster and better in the future.

            Output format — use EXACTLY these headers, then a --- separator, then markdown body:
            SKILL_NAME: kebab-case-name-max-5-words
            SKILL_DESCRIPTION: One sentence describing what this skill does
            SKILL_CATEGORY: one of: research, productivity, automation, devops, general, software-development
            SKILL_TAGS: tag1,tag2,tag3
            ---
            # Skill: Human Readable Name

            ## Purpose
            When to use this skill and what it accomplishes.

            ## Steps
            1. Step one
            2. Step two

            ## Tools Used
            - tool_name: how it is used

            ## Example Trigger
            "Exact user phrasing that should invoke this skill"

            Rules:
            - Only generate a skill if the task is genuinely reusable (not a one-off personal request)
            - Keep the skill general enough to apply to similar future tasks
            - Steps should be concrete and actionable
            - If the task is too specific or trivial, respond with: SKIP
        """.trimIndent()
    }
}
