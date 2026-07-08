package com.hermes.agent.domain.skill

/**
 * Helpers for the frontmatter/body structure of a SKILL.md document, shared by
 * the trace-reflective refiner and the weekly SkillImprovementWorker so both
 * edit skills the same way (body-only rewrite preserves name/description/tags).
 */
object SkillDoc {

    /** The markdown body after the closing `---` of the YAML frontmatter. */
    fun extractBody(content: String): String {
        val idx = content.indexOf("\n---\n", content.indexOf("---") + 1)
        return if (idx >= 0) content.substring(idx + 5) else content
    }

    /** Replace only the body, preserving the original frontmatter. */
    fun replaceBody(original: String, newBody: String): String {
        val idx = original.indexOf("\n---\n", original.indexOf("---") + 1)
        return if (idx >= 0) original.substring(0, idx + 5) + newBody else original
    }

    /** Bump the patch component of a semver string (1.2.3 -> 1.2.4). */
    fun bumpPatch(version: String): String {
        val parts = version.split(".")
        return if (parts.size == 3) {
            "${parts[0]}.${parts[1]}.${(parts[2].toIntOrNull() ?: 0) + 1}"
        } else version
    }
}
