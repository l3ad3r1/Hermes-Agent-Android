package com.hermes.agent.data.agent

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.data.llm.RoutingContext
import com.hermes.agent.data.local.BotThreads
import com.hermes.agent.data.local.LocalBotStore
import com.hermes.agent.data.memory.ConversationLearner
import com.hermes.agent.data.memory.UserModelService
import com.hermes.agent.data.remote.ChiefOfBots
import com.hermes.agent.data.tool.ToolCallExecutor
import com.hermes.agent.domain.agent.AgentRouter
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.data.tools.DeferredToolScope
import com.hermes.agent.data.tools.ToolSearchEngine
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.RoutingResult
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.StandingInstructions
import com.hermes.agent.domain.model.ExecutionPlan
import com.hermes.agent.domain.model.ExecutionStep
import com.hermes.agent.domain.ledger.ActivityLedger
import com.hermes.agent.domain.model.ActivityEntry
import com.hermes.agent.domain.model.ActivityKind
import com.hermes.agent.domain.model.StepStatus
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolExecutionDecision
import com.hermes.agent.domain.tool.ToolExecutionPolicy
import com.hermes.agent.domain.tool.ToolResult
import com.hermes.agent.util.DispatcherProvider
import com.hermes.agent.util.IdGenerator
import com.hermes.agent.domain.agent.AgentActivity
import com.hermes.agent.domain.agent.AgentPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Context size assumed when deciding whether MCP/plugin tool schemas should
 * hide behind the tool-search bridge.
 *
 * The tools array is built before the router picks a provider, so the real
 * context of the model that will serve the turn is not known here. This is a
 * deliberate fixed assumption: high enough that a handful of MCP tools stay
 * inline (deferring them costs an extra round trip), low enough that a large
 * catalogue is hidden before it crowds out the conversation. Revisit if the
 * routing decision ever moves ahead of tool assembly.
 */
private const val ASSUMED_CONTEXT_TOKENS = 32_768

/** A reply that begins with a tool-call envelope, tagged (`<tool_call>`) or bare (`tool_call {`). */
private val RAW_TOOL_CALL_START = Regex("""^\s*(?:<tool_?call>|tool_call\b)""", RegexOption.IGNORE_CASE)

/** An assistant message that is nothing but a tool call that never ran. */
internal fun isRawToolCallReply(message: LlmMessage): Boolean =
    message.role == "assistant" && RAW_TOOL_CALL_START.containsMatchIn(message.content)


/**
 * Default [Orchestrator] implementation.
 *
 * Wires together routing, agent personas, tool execution, and the
 * closed self-improvement learning loop:
 *
 *   1. Route user message → RoutingResult.
 *   2. Load memories + user model → inject into system prompt.
 *   3. Execute steps (tool-call loop per step).
 *   4. After completion, fire off [ConversationLearner] (extract new
 *      facts → memory) and [AutonomousSkillCreator] (generate skill if
 *      complex task detected) in the background [learningScope].
 *   5. Notify [UserModelService] so it can rebuild the user profile
 *      every N conversations.
 */
@Singleton
class OrchestratorImpl @Inject constructor(
    private val agentRouter: AgentRouter,
    private val agentRegistry: AgentRegistry,
    private val toolRegistry: ToolRegistry,
    private val deferredToolScope: DeferredToolScope,
    private val llmRouter: LlmRouter,
    private val agentLoopRunner: AgentLoopRunner,
    private val deterministicPhoneCommandRouter: DeterministicPhoneCommandRouter,
    private val toolCallExecutor: ToolCallExecutor,
    private val toolExecutionPolicy: ToolExecutionPolicy,
    private val dispatchers: DispatcherProvider,
    private val memoryRepository: MemoryRepository,
    private val conversationLearner: ConversationLearner,
    private val toolConfirmationService: com.hermes.agent.domain.tool.ToolConfirmationService,
    private val autonomousSkillCreator: AutonomousSkillCreator,
    private val userModelService: UserModelService,
    private val skillMatcher: SkillMatcher,
    private val ragPipeline: com.hermes.agent.domain.rag.RagPipeline,
    private val executionPlanRepository: ExecutionPlanRepository,
    private val activityLedger: ActivityLedger,
    private val settingsRepository: com.hermes.agent.domain.settings.SettingsRepository,
    private val localBotStore: LocalBotStore,
) : Orchestrator {

    // Supervisor scope for fire-and-forget post-turn learning tasks.
    private val learningScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    override fun run(
        conversationId: String,
        userMessage: String,
        recentMessages: List<LlmMessage>,
        origin: ExecutionOrigin,
    // channelFlow, not flow: the agent loop runs inside DeferredToolScope.withScope, which adds a
    // thread-local element to the coroutine context, and its callbacks emit from there. A plain
    // flow refuses that ("Flow invariant is violated") and kills the turn on any deferred tool
    // call; channelFlow's send is context-safe.
    ): Flow<OrchestratorEvent> = channelFlow {

        // High-confidence phone commands bypass model inference entirely.
        // The same execution policy, confirmation UI, ledger, and tool registry
        // used by the LLM path remain authoritative.
        val deterministic = if (origin == ExecutionOrigin.INTERACTIVE) {
            deterministicPhoneCommandRouter.match(userMessage)
        } else null
        if (deterministic != null) {
            AgentActivity.setPhase(AgentPhase.SOLVING)
            val routing = RoutingResult.Solo(deterministic.role, confidence = 1f)
            val plan = buildPlan(conversationId, userMessage, routing)
            executionPlanRepository.save(plan)
            send(OrchestratorEvent.PlanReady(plan))
            val step = plan.steps.single()
            executionPlanRepository.markStepRunning(step.id)
            send(OrchestratorEvent.StepStarted(step.id, step.agentRole))

            val tool = toolRegistry.byName(deterministic.call.name)
            val result = if (tool == null) {
                ToolResult.error("Phone action is unavailable: ${deterministic.call.name}")
            } else {
                val decision = toolExecutionPolicy.evaluate(
                    origin,
                    deterministic.call.name,
                    tool.descriptor.requiresConfirmation,
                )
                val mustConfirm = decision is ToolExecutionDecision.Confirm
                Timber.tag("DeterministicPhone").i(
                    "Matched tool=%s decision=%s requiresConfirmation=%s",
                    deterministic.call.name,
                    decision::class.simpleName,
                    tool.descriptor.requiresConfirmation,
                )
                send(OrchestratorEvent.ToolCallRequested(deterministic.call, mustConfirm))
                when {
                    decision is ToolExecutionDecision.Deny -> ToolResult.error(decision.reason)
                    mustConfirm && !toolConfirmationService.awaitConfirmation(deterministic.call) ->
                        ToolResult.error("Action cancelled")
                    else -> toolCallExecutor.execute(deterministic.call, confirmationGate = null)
                }
            }

            activityLedger.record(
                ActivityEntry(
                    timestamp = System.currentTimeMillis(),
                    kind = ActivityKind.TOOL_CALL,
                    origin = origin.name.lowercase(),
                    conversationId = conversationId,
                    title = deterministic.call.name,
                    detail = result.output.ifEmpty { result.errorMessage.orEmpty() }.take(500),
                    success = result.success,
                ),
            )
            send(
                OrchestratorEvent.ToolCallResult(
                    deterministic.call,
                    result.output.ifEmpty { result.errorMessage.orEmpty() },
                    result.success,
                ),
            )
            executionPlanRepository.markStepFinished(
                step.id,
                if (result.success) StepStatus.SUCCEEDED else StepStatus.FAILED,
                result.errorMessage,
            )
            send(OrchestratorEvent.StepFinished(step.id, result.success))
            val reply = if (result.success) result.output else result.errorMessage.orEmpty()
            send(OrchestratorEvent.ReplyToken(reply))
            send(OrchestratorEvent.ReplyComplete(reply, deterministic.role, isOnDevice = true))
            return@channelFlow
        }

        // 1. Route.
        AgentActivity.setPhase(AgentPhase.SOLVING)
        // A persona chat — a local bot, or the Chief of Bots' own thread — is one fixed agent: the
        // persona. Routing it by keyword sent "create a bot … that takes meeting notes" to the
        // PRODUCTIVITY agent, whose prompt describes a `todo` tool the Chief does not have, and the
        // small on-device model improvised from it: it replied with a raw `todo` call, and, asked to
        // list bots, invented "ManageBots" and "Todo".
        val routing = if (localBotStore.isLocalBot(conversationId)) {
            RoutingResult.Solo(AgentRole.CONVERSATIONAL, confidence = 1f)
        } else {
            agentRouter.route(userMessage)
        }
        val primaryRole = when (routing) {
            is RoutingResult.Solo -> routing.agent
            is RoutingResult.MultiAgent -> routing.agents.first()
            is RoutingResult.Fallback -> AgentRole.DEFAULT
        }
        Timber.tag("Orchestrator").d("Routed to %s", primaryRole)

        // 2. Build plan.
        val plan = buildPlan(conversationId, userMessage, routing)
        executionPlanRepository.save(plan)
        send(OrchestratorEvent.PlanReady(plan))

        // 3. Load memories + user model and inject into system prompt.
        // The four context lookups are independent — run them concurrently so
        // the pre-first-token wait is the slowest one, not the sum of all four.
        AgentActivity.setPhase(AgentPhase.SEARCHING)
        val contextStart = System.currentTimeMillis()
        val (memories, ragContext, userModel, skillBlockDeferred) = coroutineScope {
            val memoriesJob = async {
                runCatching { memoryRepository.searchMemories(userMessage, limit = 15) }
                    .getOrDefault(emptyList())
            }
            val ragJob = async {
                runCatching { ragPipeline.buildContext(userMessage, maxChars = 3000) }
                    .getOrDefault("")
            }
            val userModelJob = async {
                runCatching { userModelService.currentModel() }.getOrNull()
            }
            val skillJob = async {
                runCatching { skillMatcher.findRelevantSkill(userMessage) }
                    .getOrNull()
                    ?.let { skillMatcher.renderSkillBlock(it) }
                    ?: ""
            }
            ContextLookups(memoriesJob.await(), ragJob.await(), userModelJob.await(), skillJob.await())
        }
        Timber.tag("Orchestrator").d(
            "context lookups took %d ms", System.currentTimeMillis() - contextStart,
        )

        val memoryBlock = buildString {
            if (userModel != null) {
                append("\n\n## User profile\n$userModel")
            }
            val regularMemories = memories.filter {
                !it.content.startsWith(UserModelService.MODEL_PREFIX)
            }
            if (regularMemories.isNotEmpty()) {
                append("\n\n## What you know about the user\n")
                regularMemories.forEach { m -> append("- ${m.content}\n") }
                append("\nUse this context naturally. ")
                append("Save any new personal facts with the memory tool (action='add').")
            }
            if (ragContext.isNotBlank()) {
                append("\n\n## Relevant Personal Documents\n")
                append(ragContext)
                append("\nUse this context to inform your answers when asked about the user's notes or documents.")
            }
        }

        // 3.5. Skill block was fetched concurrently above (deterministic
        // lexical match — zero LLM cost; see SkillMatcher).
        val skillBlock = skillBlockDeferred

        // 4. Execute each step; collect all tool names used for learning.
        val aggregator = StringBuilder()
        val allToolsUsed = mutableListOf<String>()
        var lastProviderWasOnDevice = true
        // Tools that actually completed. A step can fail after its work landed —
        // the provider 500s, or on-device inference times out, on the round that
        // was meant to word the reply. Reporting only the failure left the user
        // with no answer at all for a task Hermes had in fact carried out.
        val completedWork = mutableListOf<String>()

        for (step in plan.steps) {
            executionPlanRepository.markStepRunning(step.id)
            send(OrchestratorEvent.StepStarted(step.id, step.agentRole))

            val agent = agentRegistry.get(step.agentRole)
            // manage_bots is deliberately granted to no role in AgentToolAccess — it is
            // added back in here, only for the one conversation belonging to the local bot
            // flagged as Chief of Bots (or the Chief's own thread), rather than to every
            // conversation of this role.
            val availableTools = when {
                // A persona chat is just a chat: offering the full catalogue to a small
                // on-device model made it improvise calls to unrelated tools (desktop_bots,
                // kanban) instead of answering. The Chief of Bots gets exactly one tool.
                localBotStore.isChiefOfBots(conversationId) ->
                    listOfNotNull(toolRegistry.byName("manage_bots")?.descriptor)
                localBotStore.isLocalBot(conversationId) -> emptyList()
                else -> agent.availableTools(toolRegistry)
            }
            // Progressive disclosure: MCP and plugin tools hide behind the three
            // bridge tools once their schemas would eat into the context. Without
            // this call the bridge tools were advertised on every turn (with
            // nothing to find) and a large MCP catalogue was sent in full. The
            // context size assumed here is fixed because routing has not happened
            // yet at this point - see ASSUMED_CONTEXT_TOKENS.
            val disclosure = ToolSearchEngine.evaluate(
                availableTools,
                contextWindowTokens = ASSUMED_CONTEXT_TOKENS,
            )
            val tools = disclosure.modelVisibleDescriptors
            // The bridge may only reach names this step already has grants for.
            // Its scope is installed around the tool loop below so concurrent
            // orchestrations cannot replace one another's grants.
            val deferredToolNames = disclosure.deferredDescriptors.map { it.name }.toSet()

            // Pin a single text tool-call format so models that don't use
            // structured tool_calls (Gemma's ```tool_code```, Nemotron's
            // <TOOLCALL>) emit the <tool_call> JSON the parser recovers.
            val previousContext = if (aggregator.isNotEmpty()) {
                "\n\n## Context from previous agents\n$aggregator"
            } else ""

            // Hiding a tool's schema is not the same as hiding the tool. With the
            // catalogue behind the bridge and nothing naming what is back there,
            // the model cannot know an MCP tool exists: asked to "use deepwiki" it
            // ran web_search and scraped deepwiki.com instead of calling
            // mcp__deepwiki__ask_question. Names and one line each cost a few
            // hundred tokens against the several thousand deferring saves.
            val deferredBlock = if (disclosure.isProgressiveDisclosureActive) {
                buildString {
                    append("\n\n## Tools available through tool_search\n")
                    append(
                        "These are ready to use; only their argument schemas are withheld " +
                            "to save room. When one of them fits the request, prefer it over a " +
                            "generic web search: call tool_describe for its arguments, then " +
                            "tool_call to run it.\n"
                    )
                    disclosure.deferredDescriptors.forEach { descriptor ->
                        append("- ")
                        append(descriptor.name)
                        append(": ")
                        append(descriptor.description.substringBefore('\n').take(110))
                        append('\n')
                    }
                }
            } else ""

            // Standing instructions: user-authored guidance that applies to every
            // turn (OpenClaw docs/automation/index.md). Screened on the way in —
            // it shares a context window with tool output, so it must not be able
            // to forge a role or a tool call. Context only: it grants nothing.
            val standingBlock = StandingInstructions.promptBlock(
                runCatching { settingsRepository.current().standingInstructions }.getOrDefault(""),
            )

            val toolInstruction = if (tools.isNotEmpty()) ToolCallPrompt.INSTRUCTION else ""
            // Two system messages so provider prompt caching (OpenAI/Gemini/DeepSeek
            // do it automatically on a stable prefix) can hit the big stable chunk —
            // tool schema + persona + standing instructions + tool-call format — every
            // turn. The per-turn recall (memory, skill match, prior-agent context)
            // goes in a second system block that the cache skips.
            // A local bot's persona is layered on top of the role's default prompt for
            // its own conversation thread, rather than replacing it — the default prompt
            // carries tool-use guardrails tuned for the on-device model, and dropping them
            // made a small model (Llama 3.2 1B) noticeably more prone to hallucinating tool
            // calls. Every other conversation is unaffected.
            val localPersona = localBotStore.systemPromptFor(conversationId)
            val persona = if (localPersona != null) {
                agent.systemPrompt + "\n\n## Custom persona for this conversation\n" + localPersona
            } else {
                agent.systemPrompt
            }
            val stableSystem = persona + standingBlock + toolInstruction
            val turnContext = memoryBlock + skillBlock + previousContext + deferredBlock
            // A persona chat is replayed to a small model as history, so what it once said badly
            // is what it says next: a reply that was only a raw `tool_call {...}` (the call never
            // ran; it was stored as if it were an answer) made the Chief answer the next request
            // with the same call again. Those rows carry nothing the model should imitate.
            val history = when {
                // Creating or removing a bot is one self-contained instruction; the thread adds
                // nothing to it and can only mislead. Given the earlier turns, the Chief answered
                // "remove the bot named scribe" by repeating its own earlier (invented) list of
                // bots word for word, and never called the tool. A fresh context cannot do that.
                BotThreads.baseOf(conversationId) == ChiefOfBots.THREAD && ChiefOfBots.looksLikeBotManagement(userMessage) ->
                    emptyList()
                localBotStore.isLocalBot(conversationId) -> recentMessages.filterNot(::isRawToolCallReply)
                else -> recentMessages
            }
            val llmMessages = buildList {
                add(LlmMessage(role = "system", content = stableSystem))
                if (turnContext.isNotBlank()) add(LlmMessage(role = "system", content = turnContext))
                addAll(history)
                if (history.none { it.role == "user" && it.content == userMessage }) {
                    add(LlmMessage(role = "user", content = userMessage))
                }
            }

            val decision = llmRouter.route(
                llmMessages,
                RoutingContext(
                    requiresReliableToolCalls = tools.isNotEmpty() &&
                        step.agentRole != AgentRole.CONVERSATIONAL,
                    // The router is handed messages, never the tool list, so it
                    // cannot otherwise tell a tool turn from a chat turn — and
                    // the on-device tool caller is only worth its prefill on the
                    // former. This is the only path that advertises tools.
                    toolCount = tools.size,
                ),
            )
            val provider = when (decision) {
                is RoutingDecision.Ready -> decision.provider
                is RoutingDecision.Unavailable -> {
                    executionPlanRepository.markStepFinished(
                        step.id,
                        StepStatus.FAILED,
                        decision.reason,
                    )
                    send(OrchestratorEvent.StepFinished(step.id, success = false))
                    send(OrchestratorEvent.Failed(withCompletedWork(decision.reason, completedWork)))
                    return@channelFlow
                }
            }
            AgentActivity.setPhase(AgentPhase.THINKING)
            val loopOutcome = try {
                deferredToolScope.withScope(deferredToolNames) {
                    agentLoopRunner.run(
                    provider = provider,
                    initialMessages = llmMessages,
                    tools = tools,
                    origin = origin,
                    onToolRequested = { call, requiresConfirmation ->
                        AgentActivity.setPhase(AgentPhase.WORKING)
                        send(OrchestratorEvent.ToolCallRequested(call, requiresConfirmation))
                    },
                    confirmationGate = ToolCallExecutor.ConfirmationGate { call, requiresConfirmation ->
                        if (requiresConfirmation) toolConfirmationService.awaitConfirmation(call) else true
                    },
                    onToolResult = { call, result ->
                        activityLedger.record(
                            ActivityEntry(
                                timestamp = System.currentTimeMillis(),
                                kind = ActivityKind.TOOL_CALL,
                                origin = origin.name.lowercase(),
                                conversationId = conversationId,
                                title = call.name,
                                detail = (result.output.ifEmpty { result.errorMessage.orEmpty() }).take(500),
                                success = result.success,
                            ),
                        )
                        if (result.success) completedWork += call.name
                        send(
                            OrchestratorEvent.ToolCallResult(
                                call = call,
                                output = result.output.ifEmpty { result.errorMessage.orEmpty() },
                                success = result.success,
                            ),
                        )
                        // The loop feeds the result back to the model, so we
                        // are waiting on inference again until it either calls
                        // another tool or starts replying.
                        AgentActivity.setPhase(AgentPhase.THINKING)
                    },
                    )
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    executionPlanRepository.markStepFinished(
                        step.id,
                        StepStatus.BLOCKED,
                        "Execution was interrupted before this step completed.",
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: "The plan step failed unexpectedly."
                executionPlanRepository.markStepFinished(step.id, StepStatus.FAILED, message)
                send(OrchestratorEvent.StepFinished(step.id, success = false))
                send(OrchestratorEvent.Failed(withCompletedWork(message, completedWork)))
                return@channelFlow
            }

            val completed = when (loopOutcome) {
                is AgentLoopOutcome.Completed -> loopOutcome
                is AgentLoopOutcome.Failed -> {
                    allToolsUsed += loopOutcome.toolsInvoked
                    executionPlanRepository.markStepFinished(
                        step.id,
                        StepStatus.FAILED,
                        loopOutcome.userMessage,
                    )
                    send(OrchestratorEvent.StepFinished(step.id, success = false))
                    send(OrchestratorEvent.Failed(withCompletedWork(loopOutcome.userMessage, completedWork)))
                    return@channelFlow
                }
            }

            lastProviderWasOnDevice = provider.isOnDevice
            allToolsUsed += completed.toolsInvoked
            aggregator.append(completed.reply)
            AgentActivity.setPhase(AgentPhase.COMPOSING)
            send(OrchestratorEvent.ReplyToken(completed.reply))
            executionPlanRepository.markStepFinished(step.id, StepStatus.SUCCEEDED)
            send(OrchestratorEvent.StepFinished(step.id, success = true))
        }

        val finalText = aggregator.toString()
        send(
            OrchestratorEvent.ReplyComplete(
                finalText = finalText,
                agentRole = primaryRole,
                isOnDevice = lastProviderWasOnDevice,
            )
        )

        // 5. Fire-and-forget learning tasks — do NOT block the UI.
        learningScope.launch {
            // Extract personal facts from this turn.
            conversationLearner.extractAndLearn(userMessage, finalText)

            // Auto-create a skill if this was a complex multi-tool task.
            if (allToolsUsed.toSet().size >= 2) {
                autonomousSkillCreator.maybeCreateSkill(userMessage, finalText, allToolsUsed)
            }

            // Update the user model every N conversations.
            userModelService.onConversationComplete()
        }
    }
        // Live "thinking" presence: any orchestrator run (chat, kanban,
        // delegate, API server) flips the process-wide activity signal the
        // home screen's eyes observe.
        .onStart { AgentActivity.begin() }
        .onCompletion { AgentActivity.end() }
        .flowOn(dispatchers.io)

    /** Build the deterministic role plan for this conversation turn. */
    private fun buildPlan(
        conversationId: String,
        userMessage: String,
        routing: RoutingResult,
    ): ExecutionPlan {
        val now = System.currentTimeMillis()
        val steps = when (routing) {
            is RoutingResult.Solo -> listOf(
                ExecutionStep(
                    id = IdGenerator.newId(),
                    agentRole = routing.agent,
                    description = "Handle user request: ${userMessage.take(80)}",
                )
            )
            is RoutingResult.MultiAgent -> buildList {
                routing.agents.forEachIndexed { i, role ->
                    val previousStepId = lastOrNull()?.id
                    add(
                        ExecutionStep(
                            id = IdGenerator.newId(),
                            agentRole = role,
                            description = if (i == 0) "Research: ${userMessage.take(80)}"
                            else "Continue using the previous agent's result.",
                            dependsOn = previousStepId?.let(::listOf).orEmpty(),
                        ),
                    )
                }
            }
            is RoutingResult.Fallback -> listOf(
                ExecutionStep(
                    id = IdGenerator.newId(),
                    agentRole = AgentRole.DEFAULT,
                    description = "Fallback: ${userMessage.take(80)}",
                )
            )
        }
        return ExecutionPlan(
            id = IdGenerator.newId(),
            conversationId = conversationId,
            userMessage = userMessage,
            steps = steps,
            createdAt = now,
        )
    }

}

/**
 * Appends the work that succeeded before [reason] stopped the turn.
 *
 * A tool result is durable: the task really was created, the message really was
 * sent. When the step that follows fails, the user has to be told what already
 * happened, or they repeat an action that has already taken effect.
 */
internal fun withCompletedWork(reason: String, completedWork: List<String>): String {
    if (completedWork.isEmpty()) return reason
    val names = completedWork.distinct().joinToString(", ")
    return "$reason\n\nThis ran first and did take effect: $names."
}

/** Results of the concurrent pre-turn context lookups. */
private data class ContextLookups(
    val memories: List<com.hermes.agent.domain.model.Memory>,
    val ragContext: String,
    val userModel: String?,
    val skillBlock: String,
)
