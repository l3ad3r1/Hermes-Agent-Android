package com.hermes.agent.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.domain.repository.SkillRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Weekly self-improvement pass over user-created skills.
 *
 * For each non-built-in skill, asks the LLM:
 *   "Can this skill be improved? If yes, rewrite the body only."
 *
 * If the LLM returns a better body (judged by length and change threshold),
 * the skill content is updated in place. The name, description, category,
 * and tags are preserved — only the markdown body is rewritten.
 *
 * This closes the third part of the self-improvement loop:
 *   1. [ConversationLearner]   — facts extracted per conversation
 *   2. [AutonomousSkillCreator] — skills created from complex tasks
 *   3. [SkillImprovementWorker] — skills refined over time
 *
 * Constraints:
 * - Only runs if the cloud provider is available (API key configured)
 * - Skips built-in skills (isBuiltIn = true)
 * - Fail-soft: any exception is caught; the worker always returns success
 *   so WorkManager doesn't retry-storm
 */
@HiltWorker
class SkillImprovementWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val skillRepository: SkillRepository,
    private val llmProvider: CloudLlmProvider,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Timber.tag("SkillImprove").i("starting weekly skill improvement pass")

        if (!llmProvider.isAvailable()) {
            Timber.tag("SkillImprove").d("cloud unavailable — skipping")
            return Result.success()
        }

        var improved = 0
        try {
            val skills = skillRepository.getAll().filter { !it.isBuiltIn }
            Timber.tag("SkillImprove").d("reviewing ${skills.size} user-created skills")

            for (skill in skills) {
                try {
                    val improved_ = improveSkill(skill.content)
                    if (improved_ != null && isSignificantImprovement(skill.content, improved_)) {
                        val updatedContent = replaceBody(skill.content, improved_)
                        skillRepository.upsert(
                            name = skill.name,
                            description = skill.description,
                            content = updatedContent,
                            category = skill.category,
                            tags = skill.tags,
                            version = bumpPatch(skill.version),
                        )
                        improved++
                        Timber.tag("SkillImprove").i("improved skill: ${skill.name}")
                    }
                } catch (e: Exception) {
                    Timber.tag("SkillImprove").w(e, "failed to improve skill ${skill.name}")
                }
            }
        } catch (t: Throwable) {
            Timber.tag("SkillImprove").w(t, "improvement pass failed")
        }

        Timber.tag("SkillImprove").i("done — improved $improved skills")
        return Result.success()
    }

    private suspend fun improveSkill(currentContent: String): String? {
        val response = llmProvider.complete(
            listOf(
                LlmMessage(role = "system", content = IMPROVE_SYSTEM),
                LlmMessage(role = "user", content = currentContent.take(2000)),
            )
        )
        val body = response.content.trim()
        return if (body.startsWith("NO_CHANGE") || body.isBlank()) null else body
    }

    private fun isSignificantImprovement(old: String, newBody: String): Boolean {
        val oldBody = extractBody(old)
        val diff = Math.abs(newBody.length - oldBody.length).toFloat()
        return diff / (oldBody.length.coerceAtLeast(1)) > 0.05f ||
            newBody.split("\n").size > oldBody.split("\n").size
    }

    private fun extractBody(content: String): String {
        val idx = content.indexOf("\n---\n", content.indexOf("---") + 1)
        return if (idx >= 0) content.substring(idx + 5) else content
    }

    private fun replaceBody(original: String, newBody: String): String {
        val idx = original.indexOf("\n---\n", original.indexOf("---") + 1)
        return if (idx >= 0) original.substring(0, idx + 5) + newBody
        else original
    }

    private fun bumpPatch(version: String): String {
        val parts = version.split(".")
        return if (parts.size == 3) {
            "${parts[0]}.${parts[1]}.${(parts[2].toIntOrNull() ?: 0) + 1}"
        } else version
    }

    companion object {
        const val UNIQUE_NAME = "hermes.skill_improvement"

        private val IMPROVE_SYSTEM = """
            You are a skill editor for the Hermes AI agent.
            Review the skill document below and rewrite ONLY the markdown body (after the --- separator).
            Improvements to make if applicable:
            - Clarify ambiguous steps
            - Add missing edge cases
            - Make the example trigger more specific and useful
            - Remove redundant instructions
            Preserve the skill's original purpose and structure.
            Return ONLY the improved markdown body (no frontmatter, no --- delimiters).
            If the skill is already well-written and needs no change, respond with exactly: NO_CHANGE
        """.trimIndent()
    }
}
