package com.hermes.agent.domain.model

/**
 * Phases of the agent execution lifecycle, surfaced via [AgentRun] for
 * real-time UI display and persisted for post-hoc debugging.
 *
 * The orchestrator transitions through these states for every user
 * message. The UI subscribes to the flow and renders a step indicator.
 */
enum class AgentRunPhase {
    /** Orchestrator received the user message and is classifying intent. */
    INTENT_CLASSIFICATION,

    /** Orchestrator is generating the execution plan. */
    PLANNING,

    /** Plan generated; awaiting user approval (only when a tool needs confirmation). */
    PENDING_APPROVAL,

    /** Executing plan steps: routing to agent(s), calling tools. */
    EXECUTING,

    /** Aggregating results from one or more agents into a final reply. */
    AGGREGATING,

    /** Final reply streaming to the UI. */
    STREAMING,

    /** Done — final message persisted. */
    COMPLETE,

    /** Aborted by user or unrecoverable error. */
    FAILED,
}

/**
 * A single step inside an [ExecutionPlan].
 *
 * Steps execute sequentially in Phase 2; Phase 3 will allow DAG-shaped
 * plans with parallel branches.
 */
data class ExecutionStep(
    val id: String,
    val agentRole: AgentRole,
    val description: String,
    val requiredTools: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val status: StepStatus = StepStatus.PENDING,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val toolCallIds: List<String> = emptyList(),
    val errorMessage: String? = null,
)

enum class StepStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED, BLOCKED, CANCELLED }

/**
 * The orchestrator's plan for handling a user message.
 *
 * Per Section 6.1 of the plan: "the orchestrator first generates a
 * high-level execution plan that identifies which agents are needed,
 * what tools each agent should invoke, and how intermediate results
 * should flow between agents. This plan is presented to the user as a
 * brief, editable outline before execution begins."
 */
data class ExecutionPlan(
    val id: String,
    val conversationId: String,
    val userMessage: String,
    val steps: List<ExecutionStep>,
    val createdAt: Long,
    val approved: Boolean = false,
)

/**
 * Mutable run-state for a single user-message round.
 *
 * Emitted as a Flow by [com.hermes.agent.domain.agent.Orchestrator] so
 * the UI can render a live progress indicator.
 */
data class AgentRun(
    val id: String,
    val plan: ExecutionPlan,
    val phase: AgentRunPhase,
    val currentStepId: String? = null,
    val toolCallResults: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
)
