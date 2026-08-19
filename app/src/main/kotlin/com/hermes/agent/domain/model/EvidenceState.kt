package com.hermes.agent.domain.model

/**
 * Represents the strict evidence boundaries for an agent's execution state,
 * as defined by Oh-My-Hermes (OMH).
 */
enum class EvidenceState(val displayName: String) {
    /** A prompt or plan is ready. Nothing has run yet. */
    PREPARED("Plan · not run"),
    
    /** An executor is running now, and the orchestrator is watching it. */
    RUNNING("Code · running"),
    
    /** A test, review, or CI gate actually passed. */
    VERIFIED("Test · verified"),
    
    /** The executor said it finished. Nobody checked the result. */
    REPORTED_DONE("Code · reported done")
}
