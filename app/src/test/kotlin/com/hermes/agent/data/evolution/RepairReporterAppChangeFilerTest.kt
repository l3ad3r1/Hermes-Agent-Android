package com.hermes.agent.data.evolution

import com.hermes.agent.data.plugin.evolution.EvolutionProposal
import com.hermes.agent.data.plugin.evolution.ProposalKind
import com.hermes.agent.data.plugin.evolution.ProposalStatus
import com.hermes.agent.data.plugin.evolution.ReviewVerdict
import com.hermes.agent.data.plugin.evolution.Verdict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairReporterAppChangeFilerTest {

    private val proposal = EvolutionProposal(
        id = "a1",
        title = "Add restaurant reservations",
        problem = "The assistant cannot book tables; asked by jane@example.com",
        evidence = "[2] refused ×3",
        kind = ProposalKind.APP_CHANGE,
        acceptanceCriteria = listOf("Can book a table", "Asks before booking"),
        signalKey = "gap:1234",
        status = ProposalStatus.READY,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `the issue follows the report form and carries spec and verdict, redacted`() {
        val body = RepairReporterAppChangeFiler.issueBody(
            proposal,
            spec = "## Summary\nAdd a booking screen. Test key sk-abcdefghijklmnopqrstuvwxyz0123",
            verdict = ReviewVerdict(Verdict.APPROVE, listOf("looks scoped")),
        )
        assertTrue(body.contains("### App\n\nHermes"))
        assertTrue(body.contains("### Component\n\nFeature evolution"))
        assertTrue(body.contains("Can book a table"))
        assertTrue(body.contains("Reviewer bot verdict:** APPROVE"))
        assertTrue(body.contains("## Summary"))
        assertFalse(body, body.contains("jane@example.com"))
        assertFalse(body, body.contains("sk-abcdefghijklmnopqrstuvwxyz0123"))
    }

    @Test
    fun `filing without bots says so`() {
        val body = RepairReporterAppChangeFiler.issueBody(proposal, spec = null, verdict = null)
        assertTrue(body.contains("Filed without builder/reviewer bots"))
    }
}
