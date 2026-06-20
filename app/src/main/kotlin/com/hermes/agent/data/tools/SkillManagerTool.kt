package com.hermes.agent.data.tools

import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exposes the Hermes skills library to the LLM.
 *
 * Mirrors the `skills_list` + `skill_view` tools from NousResearch/hermes-agent
 * using progressive disclosure:
 *   action="list"  → metadata only (name, description, version, category, tags)
 *   action="view"  → full SKILL.md content for a named skill
 */
@Singleton
class SkillManagerTool @Inject constructor(
    private val skillRepository: SkillRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "skill_manager",
        description = "Browse and load Hermes skills (reusable instruction sets). " +
            "Use action='list' to see available skills (name + description only, token-efficient). " +
            "Use action='view' with a skill name to load the full instructions for that skill.",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "'list' to show all available skills, 'view' to load one skill's full content.",
                enumValues = listOf("list", "view"),
            ),
            ToolParameter(
                name = "name",
                type = ToolParameterType.STRING,
                description = "Skill name (required when action='view').",
                required = false,
            ),
        ),
        category = "productivity",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.error("missing required parameter: action")

        return when (action) {
            "list" -> {
                val skills = skillRepository.getAll()
                if (skills.isEmpty()) return ToolResult.ok("No skills available.", System.currentTimeMillis() - start)
                val output = buildString {
                    appendLine("## Available Skills (${skills.size})")
                    appendLine()
                    skills.groupBy { it.category }.toSortedMap().forEach { (cat, group) ->
                        appendLine("### $cat")
                        group.forEach { s ->
                            append("- **${s.name}** (v${s.version}): ${s.description}")
                            if (s.tags.isNotEmpty()) append(" [${s.tags.joinToString(", ")}]")
                            appendLine()
                        }
                        appendLine()
                    }
                    appendLine("Use skill_manager(action='view', name='<skill-name>') to load full instructions.")
                }
                ToolResult.ok(output.trim(), System.currentTimeMillis() - start)
            }

            "view" -> {
                val name = (arguments["name"] as? JsonPrimitive)?.contentOrNull
                    ?: return ToolResult.error("action='view' requires parameter: name")
                val skill = skillRepository.getByName(name)
                    ?: return ToolResult.error("Skill '$name' not found. Use action='list' to see available skills.")
                val output = buildString {
                    appendLine("## Skill: ${skill.name} (v${skill.version})")
                    appendLine("**Category:** ${skill.category} | **Tags:** ${skill.tags.joinToString(", ").ifEmpty { "none" }}")
                    appendLine()
                    appendLine(skill.content)
                }
                ToolResult.ok(output.trim(), System.currentTimeMillis() - start)
            }

            else -> ToolResult.error("Unknown action '$action'. Use 'list' or 'view'.")
        }
    }
}
