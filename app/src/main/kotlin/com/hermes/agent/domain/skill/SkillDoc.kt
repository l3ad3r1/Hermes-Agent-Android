package com.hermes.agent.domain.skill

/**
 * Helpers for the frontmatter/body structure of a SKILL.md document, shared by
 * the trace-reflective refiner and the weekly SkillImprovementWorker so both
 * edit skills the same way.
 *
 * Documents written by `AutonomousSkillCreator` and `SkillManagerTool` carry
 * YAML frontmatter (name/description/version/category/tags/author) above the
 * markdown body. Built-in skills are body-only, so every helper here degrades
 * gracefully when the frontmatter is missing.
 */
object SkillDoc {

    /** Longest description we will write — matches the cap the skill creators apply. */
    const val MAX_DESCRIPTION_LENGTH = 200

    /**
     * Offset of the `\n---\n` that closes the frontmatter, or -1 for a
     * body-only document. Every helper funnels through this so they can never
     * disagree about where the frontmatter ends.
     */
    private fun closingDelimiterIndex(content: String): Int =
        content.indexOf("\n---\n", content.indexOf("---") + 1)

    /** The markdown body after the closing `---` of the YAML frontmatter. */
    fun extractBody(content: String): String {
        val idx = closingDelimiterIndex(content)
        return if (idx >= 0) content.substring(idx + 5) else content
    }

    /** Replace only the body, preserving the original frontmatter. */
    fun replaceBody(original: String, newBody: String): String {
        val idx = closingDelimiterIndex(original)
        return if (idx >= 0) original.substring(0, idx + 5) + newBody else original
    }

    /** True when the document carries a YAML frontmatter block. */
    fun hasFrontmatter(content: String): Boolean = closingDelimiterIndex(content) >= 0

    /**
     * Read a scalar key out of the frontmatter — searched in the frontmatter
     * region only, so a `description:` line inside the body can't shadow it.
     */
    fun frontmatterValue(content: String, key: String): String? {
        val idx = closingDelimiterIndex(content)
        if (idx < 0) return null
        return content.substring(0, idx).lines()
            .firstOrNull { it.trimStart().startsWith("$key:") }
            ?.substringAfter("$key:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Rewrite the frontmatter `description:` line, which is what
     * `SkillMatcher` scores retrieval against. Body-only documents are
     * returned untouched — there is no frontmatter to edit.
     *
     * The value is flattened to a single line first: a stray newline would
     * terminate the YAML scalar early and split the frontmatter in half.
     */
    fun replaceDescription(original: String, newDescription: String): String {
        val idx = closingDelimiterIndex(original)
        if (idx < 0) return original

        val sanitized = sanitizeDescription(newDescription)
        if (sanitized.isBlank()) return original

        val lines = original.substring(0, idx).lines().toMutableList()
        val existing = lines.indexOfFirst { it.trimStart().startsWith("description:") }
        if (existing >= 0) {
            lines[existing] = "description: $sanitized"
        } else {
            // Keep the conventional ordering: description follows name.
            val nameLine = lines.indexOfFirst { it.trimStart().startsWith("name:") }
            lines.add(if (nameLine >= 0) nameLine + 1 else lines.size, "description: $sanitized")
        }
        return lines.joinToString("\n") + original.substring(idx)
    }

    /** Collapse to a single trimmed line and cap at [MAX_DESCRIPTION_LENGTH]. */
    fun sanitizeDescription(description: String): String =
        description.replace(Regex("\\s+"), " ").trim().take(MAX_DESCRIPTION_LENGTH)

    /** Bump the patch component of a semver string (1.2.3 -> 1.2.4). */
    fun bumpPatch(version: String): String {
        val parts = version.split(".")
        return if (parts.size == 3) {
            "${parts[0]}.${parts[1]}.${(parts[2].toIntOrNull() ?: 0) + 1}"
        } else version
    }
}
