package com.hermes.agent.domain.skill

import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.model.SkillLifecycle

/**
 * Conditional skill activation — ported from hermes-agent's
 * `_skill_should_show` (agent/prompt_builder.py).
 *
 * Two gate types, both evaluated against the set of currently registered
 * tool names:
 *  - `requiresTools`: the skill is hidden unless EVERY required tool is
 *    available (e.g. a research skill that needs `web_search`).
 *  - `fallbackForTools`: the skill is a manual fallback and is hidden while
 *    ANY of the listed tools IS available (e.g. a "manual calendar entry"
 *    skill that only matters when `calendar_add_event` is missing).
 *
 * Archived skills are never offered regardless of gates (curator lifecycle);
 * stale skills still show — staleness only affects curator prioritization.
 */
object SkillActivation {

    fun isVisible(skill: Skill, availableTools: Set<String>): Boolean {
        if (skill.lifecycleState == SkillLifecycle.ARCHIVED) return false

        // fallback_for: hide when the primary tool IS available.
        if (skill.fallbackForTools.any { it in availableTools }) return false

        // requires: hide when a required tool is NOT available.
        if (skill.requiresTools.any { it !in availableTools }) return false

        return true
    }

    /** Partition [skills] into (visible, hidden) under [availableTools]. */
    fun partition(
        skills: List<Skill>,
        availableTools: Set<String>,
    ): Pair<List<Skill>, List<Skill>> = skills.partition { isVisible(it, availableTools) }
}
