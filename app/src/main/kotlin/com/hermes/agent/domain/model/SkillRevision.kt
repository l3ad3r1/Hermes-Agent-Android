package com.hermes.agent.domain.model

/**
 * A snapshot of a skill as it stood immediately before an edit replaced it.
 *
 * Refinement runs unattended — [com.hermes.agent.work.SkillRefineWorker]
 * applies a proposal that clears its gates without asking anyone — so a skill
 * can change while the user is not looking. Archiving the outgoing version on
 * every edit is what makes that reversible.
 */
data class SkillRevision(
    val id: String,
    val skillId: String,
    val skillName: String,
    /** The version string this snapshot carried, before the bump. */
    val version: String,
    val description: String,
    val content: String,
    /** Why it was replaced — the refiner's rationale, or a manual-edit note. */
    val note: String,
    val replacedAt: Long,
)
