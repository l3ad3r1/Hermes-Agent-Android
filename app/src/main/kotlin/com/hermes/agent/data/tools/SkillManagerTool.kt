package com.hermes.agent.data.tools

import com.hermes.agent.domain.model.SkillLifecycle
import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.domain.skill.SkillActivation
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolRegistry
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
 *
 * v0.7.23 additions ported from upstream:
 *   - Conditional activation ([SkillActivation]): skills gated by
 *     requiresTools/fallbackForTools against the live [ToolRegistry], and
 *     ARCHIVED skills are hidden from the default list.
 *   - Usage tracking: action="view" records a use (curator signal) and
 *     revives shelved skills.
 */
@Singleton
class SkillManagerTool @Inject constructor(
    private val skillRepository: SkillRepository,
    // Lazy breaks the Dagger cycle: this tool is itself registered into the
    // ToolRegistry at startup (ToolsModule); the registry is only dereferenced
    // at execute() time, long after wiring completes.
    private val toolRegistry: dagger.Lazy<ToolRegistry>,
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
                val all = skillRepository.getAll()
                if (all.isEmpty()) return ToolResult.ok("No skills available.", System.currentTimeMillis() - start)

                val availableTools = toolRegistry.get().descriptors().map { it.name }.toSet()
                val (visible, hidden) = SkillActivation.partition(all, availableTools)

                val output = buildString {
                    appendLine("## Available Skills (${visible.size})")
                    appendLine()
                    visible.groupBy { it.category }.toSortedMap().forEach { (cat, group) ->
                        appendLine("### $cat")
                        group.forEach { s ->
                            append("- **${s.name}** (v${s.version}): ${s.description}")
                            if (s.tags.isNotEmpty()) append(" [${s.tags.joinToString(", ")}]")
                            if (s.lifecycleState == SkillLifecycle.STALE) append(" (unused for a while)")
                            appendLine()
                        }
                        appendLine()
                    }
                    if (hidden.isNotEmpty()) {
                        val archived = hidden.count { it.lifecycleState == SkillLifecycle.ARCHIVED }
                        val gated = hidden.size - archived
                        append("_${hidden.size} skill(s) not shown")
                        if (gated > 0) append(" — $gated gated on unavailable/superseding tools")
                        if (archived > 0) append(", $archived archived (auto-restored on use)")
                        appendLine("._")
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
                // Curator signal: loading a skill counts as using it and
                // revives STALE/ARCHIVED skills.
                runCatching { skillRepository.recordUse(name) }
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
