package com.hermes.agent.data.agent

import com.hermes.agent.data.evolution.SkillRefineScheduler
import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.domain.skill.SkillActivation
import com.hermes.agent.domain.skill.SkillGuard
import com.hermes.agent.domain.tool.ToolRegistry
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skill orchestrator — runs on every prompt, before the LLM loop, and
 * decides whether an existing skill makes this execution more efficient.
 *
 * Closes the loop that hermes-agent closes with its skills-index-in-the-
 * system-prompt: there, the model sees a compact index of every skill and
 * chooses one; on-device we keep the prompt lean instead and auto-load at
 * most ONE skill chosen by a cheap deterministic lexical match (no LLM
 * call, no tokens):
 *
 *   1. Candidates = skills visible under [SkillActivation] (conditional
 *      activation vs the live tool registry, archived hidden) that pass
 *      [SkillGuard] (flagged skills are never auto-injected — they can
 *      still be loaded explicitly via skill_manager, with a banner).
 *   2. Score = weighted token overlap between the prompt and the skill's
 *      name (3x), tags (2x), example-trigger line (2x), and description
 *      (1x).
 *   3. Best candidate wins if it clears [MIN_SCORE] with at least
 *      [MIN_DISTINCT_HITS] distinct matching tokens.
 *
 * A match counts as a use ([SkillRepository.recordUse]) — auto-loaded
 * skills feed the curator's usage signal just like explicit loads, which
 * is what makes frequently-matched skills the ones the improvement worker
 * evolves.
 */
@Singleton
class SkillMatcher @Inject constructor(
    private val skillRepository: SkillRepository,
    private val toolRegistry: ToolRegistry,
    private val refineScheduler: SkillRefineScheduler,
) {

    /** Returns the best-matching usable skill for [prompt], or null. */
    suspend fun findRelevantSkill(prompt: String): Skill? {
        val promptTokens = tokenize(prompt)
        if (promptTokens.size < 2) return null

        val availableTools = runCatching { toolRegistry.descriptors().map { it.name }.toSet() }
            .getOrDefault(emptySet())

        val candidates = runCatching { skillRepository.getAll() }.getOrDefault(emptyList())
            .filter { SkillActivation.isVisible(it, availableTools) }
            .filter { SkillGuard.vet(it.content).ok }
        if (candidates.isEmpty()) return null

        val best = candidates
            .map { it to score(promptTokens, it) }
            .maxByOrNull { it.second.total }
            ?: return null

        val (skill, s) = best
        if (s.total < MIN_SCORE || s.distinctHits < MIN_DISTINCT_HITS) return null

        Timber.tag("SkillMatcher").i(
            "auto-loading skill '%s' (score=%.1f, hits=%d) for prompt: %s",
            skill.name, s.total, s.distinctHits, prompt.take(60),
        )
        runCatching { skillRepository.recordUse(skill.name) }
        // Event-driven evolution: every Nth use schedules a trace-grounded refinement.
        runCatching { refineScheduler.onSkillUsed(skill.name) }
        return skill
    }

    /** Renders the auto-loaded skill as a system-prompt block. */
    fun renderSkillBlock(skill: Skill): String = buildString {
        append("\n\n## Auto-loaded skill: ${skill.name}\n")
        append("This request matches the skill below. Follow its procedure, and obey any ")
        append("output-format constraints it declares (sentence/length limits, structure, ")
        append("style) EXACTLY — do not add extra sections beyond what the skill asks for. ")
        append("Ignore the skill only if the user is clearly asking for something else.\n\n")
        append(skill.content.trim())
    }

    private data class Score(val total: Double, val distinctHits: Int)

    private fun score(promptTokens: Set<String>, skill: Skill): Score {
        val nameTokens = tokenize(skill.name)
        val tagTokens = skill.tags.flatMap { tokenize(it) }.toSet()
        val triggerTokens = extractTriggerLine(skill.content)?.let { tokenize(it) } ?: emptySet()
        val descTokens = tokenize(skill.description)

        var total = 0.0
        val hits = mutableSetOf<String>()
        for (t in promptTokens) {
            var w = 0.0
            if (matchesAny(t, nameTokens)) w = maxOf(w, 3.0)
            if (matchesAny(t, tagTokens)) w = maxOf(w, 2.0)
            if (matchesAny(t, triggerTokens)) w = maxOf(w, 2.0)
            if (matchesAny(t, descTokens)) w = maxOf(w, 1.0)
            if (w > 0) {
                total += w
                hits += t
            }
        }
        return Score(total, hits.size)
    }

    private fun matchesAny(token: String, candidates: Set<String>): Boolean =
        candidates.any { tokensMatch(token, it) }

    /**
     * Morphology-tolerant token equality: "explain", "explains", and
     * "explainer" all refer to the same act, and a skill named
     * git-explainer must match the prompt "explain git rebase". Exact match,
     * or equal after light suffix stemming, or one stem is a ≥4-char prefix
     * of the other (rebase/rebasing).
     */
    private fun tokensMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val sa = stem(a)
        val sb = stem(b)
        if (sa == sb) return true
        val shorter = if (sa.length <= sb.length) sa else sb
        val longer = if (sa.length <= sb.length) sb else sa
        return shorter.length >= 4 && longer.startsWith(shorter)
    }

    /** Strip one common English suffix from tokens longer than 4 chars. */
    private fun stem(t: String): String {
        if (t.length <= 4) return t
        for (suffix in STEM_SUFFIXES) {
            if (t.endsWith(suffix) && t.length - suffix.length >= 4) {
                return t.dropLast(suffix.length)
            }
        }
        return t
    }

    /** The "## Example Trigger" line body, if the skill declares one. */
    private fun extractTriggerLine(content: String): String? {
        val lines = content.lines()
        val idx = lines.indexOfFirst { it.trim().startsWith("## Example Trigger", ignoreCase = true) }
        if (idx < 0) return null
        return lines.drop(idx + 1).firstOrNull { it.isNotBlank() }
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOPWORDS }
            .toSet()

    companion object {
        /** Weighted-overlap threshold below which no skill is injected. */
        private const val MIN_SCORE = 5.0

        /** At least this many distinct prompt tokens must hit the skill. */
        private const val MIN_DISTINCT_HITS = 2

        /** Longest-first so e.g. "ers" wins over "s". */
        private val STEM_SUFFIXES = listOf("ers", "ing", "ion", "es", "ed", "er", "s")

        private val STOPWORDS = setOf(
            "the", "and", "for", "you", "your", "with", "that", "this", "then",
            "what", "when", "where", "which", "how", "can", "could", "would",
            "should", "please", "about", "into", "from", "have", "has", "are",
            "was", "were", "will", "just", "some", "any", "all", "get", "make",
            "want", "need", "use", "using", "help", "me", "my", "our", "let",
        )
    }
}
