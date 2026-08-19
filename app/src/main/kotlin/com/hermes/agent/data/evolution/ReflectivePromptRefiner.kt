package com.hermes.agent.data.evolution

import com.hermes.agent.data.agent.AgentRegistry
import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.RoutingContext
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.domain.harness.PromptConstraints
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.repository.SupplementalPromptRepository
import com.hermes.agent.domain.skill.SkillGuard
import com.hermes.agent.util.DispatcherProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The continual-harness analogue of [ReflectiveSkillRefiner]: reflects on how
 * an agent role actually performed and proposes updated *supplemental*
 * guidance for it.
 *
 * The base system prompt is shown to the model as read-only context and is
 * never an output. That boundary is the whole point of the design — the base
 * prompt declares which tools exist and how the agent is wired, and a
 * self-modifying loop that could rewrite it would be able to talk itself out
 * of its own guard rails.
 *
 * Unlike skill refinement there is no unattended worker for this. A skill only
 * affects turns where it is retrieved; a supplemental prompt affects every
 * single turn that role handles, so it changes only when a human approves it.
 */
@Singleton
class ReflectivePromptRefiner @Inject constructor(
    private val promptRepository: SupplementalPromptRepository,
    private val traceCollector: PromptTraceCollector,
    private val agentRegistry: AgentRegistry,
    private val llmRouter: LlmRouter,
    private val dispatchers: DispatcherProvider,
) {

    data class Proposal(
        val role: AgentRole,
        val originalContent: String,
        val proposedContent: String,
        val rationale: String,
        val traceCount: Int,
        val constraints: List<PromptConstraints.Result>,
    ) {
        val constraintsPass: Boolean get() = PromptConstraints.allPass(constraints)
        val changed: Boolean get() = proposedContent.trim() != originalContent.trim()
    }

    sealed class Outcome {
        data class Ready(val proposal: Proposal) : Outcome()
        data class NoChange(val reason: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    suspend fun refine(role: AgentRole): Outcome = withContext(dispatchers.io) {
        val traces = traceCollector.collectFor(role)
        if (traces.isEmpty()) {
            return@withContext Outcome.NoChange(
                "No recent conversations were handled by the ${role.displayName} agent yet.",
            )
        }

        val current = promptRepository.get(role)?.content.orEmpty()
        val basePrompt = runCatching { agentRegistry.get(role).systemPrompt }.getOrNull()
            ?: return@withContext Outcome.Failed("No agent is registered for ${role.displayName}.")

        val messages = listOf(
            LlmMessage(role = "system", content = SYSTEM),
            LlmMessage(role = "user", content = buildPrompt(role, basePrompt, current, traces)),
        )

        // Routed, not pinned to one provider: the chain fails over across every
        // configured cloud model on 401/402/429/5xx, so a single expired key or
        // an exhausted rate limit no longer ends the attempt. cloudOnly keeps
        // the on-device model out of it.
        val decision = llmRouter.route(messages, RoutingContext(cloudOnly = true))
        if (decision is RoutingDecision.Unavailable) {
            return@withContext Outcome.Failed(decision.reason)
        }

        val response = runCatching { decision.provider.complete(messages) }
            .onFailure { Timber.tag("PromptRefiner").w(it, "LLM call failed") }
            .getOrNull()
            ?: return@withContext Outcome.Failed(
                "Every configured cloud model failed — check Settings → Cloud, then try again.",
            )

        val parsed = parse(response.content)
            ?: return@withContext Outcome.NoChange(
                "The model saw no worthwhile change from these traces.",
            )

        val verdict = SkillGuard.vet(parsed.content)
        if (!verdict.ok) {
            return@withContext Outcome.Failed(
                "Proposed guidance rejected by Skills Guard: ${verdict.flags.joinToString()}",
            )
        }

        val proposal = Proposal(
            role = role,
            originalContent = current,
            proposedContent = parsed.content,
            rationale = parsed.rationale.ifBlank { "Refined from ${traces.size} traces." },
            traceCount = traces.size,
            constraints = PromptConstraints.validate(parsed.content, baseline = current),
        )

        if (!proposal.changed) {
            Outcome.NoChange("The refined guidance is identical to the current one.")
        } else {
            Outcome.Ready(proposal)
        }
    }

    /** Persist an approved proposal; the repository archives what it replaces. */
    suspend fun apply(proposal: Proposal) = withContext(dispatchers.io) {
        val current = promptRepository.get(proposal.role)
        promptRepository.put(
            role = proposal.role,
            content = proposal.proposedContent,
            version = nextVersion(current?.version),
            revisionNote = "Refined from ${proposal.traceCount} trace(s): ${proposal.rationale}",
        )
        Timber.tag("PromptRefiner").i("applied supplemental prompt for %s", proposal.role.name)
    }

    private fun nextVersion(current: String?): String {
        if (current == null) return "1.0.0"
        val parts = current.split(".")
        return if (parts.size == 3) {
            "${parts[0]}.${parts[1]}.${(parts[2].toIntOrNull() ?: 0) + 1}"
        } else "1.0.0"
    }

    private data class Parsed(val rationale: String, val content: String)

    private fun parse(raw: String): Parsed? {
        val text = raw.trim()
        if (text.isBlank() || text.uppercase().startsWith("NO_CHANGE")) return null

        val marker = "---NOTES---"
        val idx = text.indexOf(marker)
        if (idx < 0) return null

        val content = text.substring(idx + marker.length).trim()
        if (content.length < PromptConstraints.MIN_PROMPT_CHARS) return null

        val rationale = text.substring(0, idx).lines()
            .firstOrNull { it.trimStart().startsWith("RATIONALE:") }
            ?.substringAfter("RATIONALE:")
            ?.trim()
            .orEmpty()

        return Parsed(rationale, content)
    }

    private fun buildPrompt(
        role: AgentRole,
        basePrompt: String,
        current: String,
        traces: List<PromptTraceCollector.Trace>,
    ): String = buildString {
        appendLine("AGENT: ${role.displayName} — ${role.description}")
        appendLine()
        appendLine("BASE PROMPT (read-only, you cannot change this):")
        appendLine(basePrompt.take(3000))
        appendLine()
        appendLine("CURRENT SUPPLEMENTAL NOTES:")
        appendLine(current.ifBlank { "(none yet)" })
        appendLine()
        appendLine("REAL TRACES handled by this agent on the user's device:")
        traces.forEachIndexed { i, t ->
            appendLine("--- Trace ${i + 1} ---")
            appendLine("User: ${t.task}")
            appendLine("Assistant: ${t.response}")
        }
    }

    companion object {
        private val SYSTEM = """
            You tune the operating notes for one agent inside the Hermes AI assistant.

            You are shown that agent's BASE PROMPT, its CURRENT SUPPLEMENTAL NOTES, and
            real traces of work it did on the user's device. You may only rewrite the
            supplemental notes. The base prompt is fixed: do not restate it, contradict
            it, or claim tools it does not list.

            Good supplemental notes capture what the traces reveal about this specific
            user that the base prompt could not know in advance — recurring preferences,
            formats they keep asking for, mistakes the agent repeated, context it kept
            having to re-derive. Write durable standing guidance, not a summary of any
            one conversation, and never record personal facts (those belong in memory).

            Be ruthless about length. These notes are prepended to every single call
            this agent makes, so keep them under 2000 characters and drop anything that
            has stopped earning its place.

            Output EXACTLY this format:
            RATIONALE: <one line: what you changed and which traces motivated it>
            ---NOTES---
            <the complete supplemental notes, replacing the current ones>

            If the traces reveal no worthwhile change, respond with exactly: NO_CHANGE
        """.trimIndent()
    }
}
