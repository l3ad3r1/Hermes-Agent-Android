package com.hermes.agent.data.evolution

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.RoutingContext
import com.hermes.agent.data.llm.RoutingDecision
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
 * device traces and asks for a single improved version, then gates the result
 * (Skills Guard + [SkillConstraints]) and hands a diff to the user to approve.
 *
 * Refines both halves of a skill document:
 *  - the **body**, which is what the agent follows once a skill is loaded;
 *  - the **description**, which is what
 *    [com.hermes.agent.data.agent.SkillMatcher] scores to decide whether to
 *    load the skill at all. A skill with a good body and a mismatched
 *    description is never retrieved, and no amount of body rewriting fixes it.
 *
 * Human-in-loop by design — [refine] only proposes; [apply] persists, and
 * every apply archives the outgoing version so it can be rolled back.
 */
@Singleton
class ReflectiveSkillRefiner @Inject constructor(
    private val skillRepository: SkillRepository,
    private val traceCollector: SkillTraceCollector,
    private val llmRouter: LlmRouter,
    private val dispatchers: DispatcherProvider,
) {

    data class Proposal(
        val skillName: String,
        val originalContent: String,
        val proposedContent: String,
        val originalDescription: String,
        val proposedDescription: String,
        val rationale: String,
        val traceCount: Int,
        val constraints: List<SkillConstraints.Result>,
    ) {
        val constraintsPass: Boolean get() = SkillConstraints.allPass(constraints)
        val bodyChanged: Boolean get() = proposedContent.trim() != originalContent.trim()
        val descriptionChanged: Boolean
            get() = proposedDescription.trim() != originalDescription.trim()
        val changed: Boolean get() = bodyChanged || descriptionChanged
    }

    sealed class Outcome {
        data class Ready(val proposal: Proposal) : Outcome()
        data class NoChange(val reason: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    suspend fun refine(skillName: String): Outcome = withContext(dispatchers.io) {
        val skill = skillRepository.getByName(skillName)
            ?: return@withContext Outcome.Failed("Skill '$skillName' not found.")

        val traces = traceCollector.collectFor(skillName, skill.content)
        if (traces.isEmpty()) {
            return@withContext Outcome.NoChange(
                "No recent conversations exercised this skill yet — use it a few times first.",
            )
        }

        val messages = listOf(
            LlmMessage(role = "system", content = SYSTEM),
            LlmMessage(
                role = "user",
                content = buildPrompt(skill.description, skill.content, traces),
            ),
        )

        // Routed rather than pinned to the primary provider, so one expired key
        // or an exhausted rate limit fails over to the next configured cloud
        // model instead of ending the attempt. cloudOnly excludes the on-device
        // model: a 1B rewrite would be persisted as the skill.
        val decision = llmRouter.route(messages, RoutingContext(cloudOnly = true))
        if (decision is RoutingDecision.Unavailable) {
            return@withContext Outcome.Failed(decision.reason)
        }

        val response = runCatching { decision.provider.complete(messages) }
            .onFailure { Timber.tag("SkillRefiner").w(it, "LLM call failed") }
            .getOrNull()
            ?: return@withContext Outcome.Failed(
                "Every configured cloud model failed — check Settings → Cloud, then try again.",
            )

        val parsed = parse(response.content)
            ?: return@withContext Outcome.NoChange("The model saw no worthwhile change from these traces.")

        // Skills Guard: never propose a rewrite carrying injection/exfil/destructive
        // text. The description ships in the retrieval index, so it is vetted too.
        val verdict = SkillGuard.vet(parsed.body + "\n" + parsed.description)
        if (!verdict.ok) {
            return@withContext Outcome.Failed(
                "Proposed rewrite rejected by Skills Guard: ${verdict.flags.joinToString()}",
            )
        }

        // An omitted DESCRIPTION means "leave it alone", not "blank it".
        val proposedDescription = parsed.description
            .takeIf { it.isNotBlank() }
            ?.let { SkillDoc.sanitizeDescription(it) }
            ?: skill.description

        val baselineBody = SkillDoc.extractBody(skill.content)
        val proposedContent = SkillDoc.replaceDescription(
            SkillDoc.replaceBody(skill.content, parsed.body),
            proposedDescription,
        )
        val constraints = SkillConstraints.validate(
            body = parsed.body,
            baselineBody = baselineBody,
            description = proposedDescription,
        )

        val proposal = Proposal(
            skillName = skillName,
            originalContent = skill.content,
            proposedContent = proposedContent,
            originalDescription = skill.description,
            proposedDescription = proposedDescription,
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

    /**
     * Persist an approved proposal as a patch-version bump. The repository
     * archives the outgoing version first, so this stays reversible from the
     * skill's revision history.
     */
    suspend fun apply(proposal: Proposal) = withContext(dispatchers.io) {
        val skill = skillRepository.getByName(proposal.skillName) ?: return@withContext
        val next = SkillDoc.bumpPatch(skill.version)
        skillRepository.upsert(
            name = skill.name,
            description = proposal.proposedDescription,
            content = proposal.proposedContent,
            category = skill.category,
            tags = skill.tags,
            version = next,
            requiresTools = skill.requiresTools,
            fallbackForTools = skill.fallbackForTools,
            revisionNote = "Refined from ${proposal.traceCount} trace(s): ${proposal.rationale}",
        )
        Timber.tag("SkillRefiner").i("applied refined skill '${skill.name}' -> v$next")
    }

    private data class Parsed(
        val description: String,
        val rationale: String,
        val body: String,
    )

    private fun parse(raw: String): Parsed? {
        val text = raw.trim()
        if (text.isBlank() || text.uppercase().startsWith("NO_CHANGE")) return null

        val marker = "---BODY---"
        val idx = text.indexOf(marker)
        if (idx < 0) {
            // No marker — treat the whole response as a body-only rewrite.
            return if (text.length < SkillConstraints.MIN_BODY_LENGTH) {
                null
            } else {
                Parsed(description = "", rationale = "", body = text)
            }
        }

        val head = text.substring(0, idx)
        val body = text.substring(idx + marker.length).trim()
        if (body.length < SkillConstraints.MIN_BODY_LENGTH) return null

        return Parsed(
            description = headerValue(head, "DESCRIPTION:"),
            rationale = headerValue(head, "RATIONALE:"),
            body = body,
        )
    }

    /**
     * Pull one `KEY: value` header out of the pre-body section. Reads to the
     * end of that line only, so the headers cannot swallow one another
     * whatever order the model emits them in.
     */
    private fun headerValue(head: String, key: String): String =
        head.lines()
            .firstOrNull { it.trimStart().startsWith(key) }
            ?.substringAfter(key)
            ?.trim()
            .orEmpty()

    private fun buildPrompt(
        description: String,
        skillContent: String,
        traces: List<SkillTraceCollector.Trace>,
    ): String = buildString {
        appendLine("CURRENT DESCRIPTION:")
        appendLine(description)
        appendLine()
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
            document, its one-line description, and real traces of how it was used on
            the user's device.

            Two things need to be right, and they fail in different ways:

            1. The DESCRIPTION decides whether the agent loads this skill at all. It
               is matched against the user's wording before the body is ever seen. If
               the traces show the user asking for this capability in words the
               description does not contain, the description is the bug — widen it to
               use the user's own vocabulary. Keep it to one line, under 200
               characters, and concrete about when the skill applies.

            2. The BODY is what the agent follows once the skill is loaded. Reflect on
               the traces: where did the instructions fall short, stay ambiguous, or
               miss a step the real tasks needed? Rewrite the markdown body so future
               runs handle those cases better.

            Preserve the skill's original purpose, keep the body concise (well under
            15 KB), retain at least one markdown heading, and do not invent
            capabilities the agent does not have.

            Output EXACTLY this format:
            DESCRIPTION: <the one-line description, unchanged if it is already right>
            RATIONALE: <one line: what you changed and which traces motivated it>
            ---BODY---
            <the improved markdown body — no frontmatter, no --- delimiters>

            If the traces reveal no worthwhile improvement to either, respond with
            exactly: NO_CHANGE
        """.trimIndent()
    }
}
