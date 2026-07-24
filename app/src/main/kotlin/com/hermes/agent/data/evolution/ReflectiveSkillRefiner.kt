package com.hermes.agent.data.evolution

import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.domain.skill.SkillConstraints
import com.hermes.agent.domain.skill.SkillDoc
import com.hermes.agent.domain.skill.SkillGuard
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device, trace-reflective skill refiner — the GEPA insight adapted for a
 * phone. Instead of a genetic loop of hundreds of LLM calls, it does ONE
 * reflective pass: it shows the model how a skill actually performed on real
 * device traces and asks for a single improved body, then gates the result
 * (Skills Guard + [SkillConstraints]) and hands a diff to the user to approve.
 *
 * Human-in-loop by design — [refine] only proposes; [apply] persists.
 */
@Singleton
class ReflectiveSkillRefiner @Inject constructor(
    private val skillRepository: SkillRepository,
    private val traceCollector: SkillTraceCollector,
    private val llmProvider: CloudLlmProvider,
    private val dispatchers: DispatcherProvider,
) {

    data class Proposal(
        val skillName: String,
        val originalContent: String,
        val proposedContent: String,
        val rationale: String,
        val traceCount: Int,
        val constraints: List<SkillConstraints.Result>,
    ) {
        val constraintsPass: Boolean get() = SkillConstraints.allPass(constraints)
        val changed: Boolean get() = proposedContent.trim() != originalContent.trim()
    }

    sealed class Outcome {
        data class Ready(val proposal: Proposal) : Outcome()
        data class NoChange(val reason: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    suspend fun refine(skillName: String): Outcome = withContext(dispatchers.io) {
        if (!llmProvider.isAvailable()) {
            return@withContext Outcome.Failed("Cloud LLM is not configured (Settings → Cloud).")
        }
        val skill = skillRepository.getByName(skillName)
            ?: return@withContext Outcome.Failed("Skill '$skillName' not found.")

        val traces = traceCollector.collectFor(skillName, skill.content)
        if (traces.isEmpty()) {
            return@withContext Outcome.NoChange(
                "No recent conversations exercised this skill yet — use it a few times first.",
            )
        }

        val response = runCatching {
            llmProvider.complete(
                listOf(
                    LlmMessage(role = "system", content = SYSTEM),
                    LlmMessage(role = "user", content = buildPrompt(skill.content, traces)),
                ),
            )
        }.onFailure { Timber.tag("SkillRefiner").w(it, "LLM call failed") }
            .getOrNull()
            ?: return@withContext Outcome.Failed("The model could not be reached — try again.")

        val parsed = parse(response.content)
            ?: return@withContext Outcome.NoChange("The model saw no worthwhile change from these traces.")

        // Skills Guard: never propose a rewrite carrying injection/exfil/destructive text.
        val verdict = SkillGuard.vet(parsed.body)
        if (!verdict.ok) {
            return@withContext Outcome.Failed(
                "Proposed rewrite rejected by Skills Guard: ${verdict.flags.joinToString()}",
            )
        }

        val baselineBody = SkillDoc.extractBody(skill.content)
        val proposedContent = SkillDoc.replaceBody(skill.content, parsed.body)
        val constraints = SkillConstraints.validate(parsed.body, baselineBody = baselineBody)

        val proposal = Proposal(
            skillName = skillName,
            originalContent = skill.content,
            proposedContent = proposedContent,
            rationale = parsed.rationale.ifBlank { "Refined from ${traces.size} usage traces." },
            traceCount = traces.size,
            constraints = constraints,
        )

        if (!proposal.changed) {
            Outcome.NoChange("The refined skill is identical to the current one.")
        } else {
            Outcome.Ready(proposal)
        }
    }

    /** Persist an approved proposal as a patch-version bump (body-only change). */
    suspend fun apply(proposal: Proposal) = withContext(dispatchers.io) {
        val skill = skillRepository.getByName(proposal.skillName) ?: return@withContext
        skillRepository.upsert(
            name = skill.name,
            description = skill.description,
            content = proposal.proposedContent,
            category = skill.category,
            tags = skill.tags,
            version = SkillDoc.bumpPatch(skill.version),
            requiresTools = skill.requiresTools,
            fallbackForTools = skill.fallbackForTools,
        )
        Timber.tag("SkillRefiner").i("applied refined skill '${skill.name}' -> v${SkillDoc.bumpPatch(skill.version)}")
    }

    private data class Parsed(val rationale: String, val body: String)

    private fun parse(raw: String): Parsed? {
        val text = raw.trim()
        if (text.isBlank() || text.uppercase().startsWith("NO_CHANGE")) return null

        val marker = "---BODY---"
        val idx = text.indexOf(marker)
        return if (idx >= 0) {
            val head = text.substring(0, idx)
            val body = text.substring(idx + marker.length).trim()
            val rationale = head.substringAfter("RATIONALE:", "").trim()
            if (body.length < SkillConstraints.MIN_BODY_LENGTH) null else Parsed(rationale, body)
        } else {
            // No marker — treat the whole response as the body.
            if (text.length < SkillConstraints.MIN_BODY_LENGTH) null else Parsed("", text)
        }
    }

    private fun buildPrompt(skillContent: String, traces: List<SkillTraceCollector.Trace>): String =
        buildString {
            appendLine("CURRENT SKILL:")
            appendLine(skillContent.take(4000))
            appendLine()
            appendLine("REAL USAGE TRACES (how this skill was actually used on-device):")
            traces.forEachIndexed { i, t ->
                appendLine("--- Trace ${i + 1} ---")
                appendLine("User: ${t.task}")
                if (t.response.isNotBlank()) appendLine("Assistant: ${t.response}")
            }
        }

    companion object {
        private val SYSTEM = """
            You are a skill editor for the Hermes AI agent. You are given a SKILL.md
            document and real traces of how it was used on the user's device.

            Reflect on the traces: where did the skill's instructions fall short, stay
            ambiguous, or miss a step that the real tasks needed? Then rewrite ONLY the
            markdown body (after the frontmatter) so future runs handle these cases
            better. Preserve the skill's original purpose, keep it concise (well under
            15 KB), and do not invent capabilities the agent doesn't have.

            Output EXACTLY this format:
            RATIONALE: <one line: what you changed and which traces motivated it>
            ---BODY---
            <the improved markdown body — no frontmatter, no --- delimiters>

            If the traces reveal no worthwhile improvement, respond with exactly: NO_CHANGE
        """.trimIndent()
    }
}
