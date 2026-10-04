package com.hermes.agent.data.evolution

import com.hermes.agent.data.plugin.evolution.EvolutionModuleVersion
import com.hermes.agent.data.plugin.evolution.EvolutionProposal
import com.hermes.agent.data.plugin.evolution.ProposalKind
import com.hermes.agent.data.plugin.evolution.ProposalStatus
import com.hermes.agent.data.plugin.evolution.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvolutionEntityMappingTest {

    private val proposal = EvolutionProposal(
        id = "p1",
        title = "Fix search",
        problem = "fails",
        evidence = "[1] ×4",
        kind = ProposalKind.MODULE_FIX,
        acceptanceCriteria = listOf("line one\nline two", "has, commas"),
        targetTool = "web_search",
        signalKey = "tool_failure:web_search:abcd",
        status = ProposalStatus.READY,
        statusMessage = "ready",
        artifact = "{\"id\":\"evo-x\"}",
        artifactSha256 = "ab".repeat(32),
        reviewVerdict = Verdict.APPROVE,
        reviewFindings = listOf("minor \"quote\""),
        testReport = "PASSED",
        rounds = 2,
        moduleId = null,
        issueUrl = null,
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Test
    fun `a proposal survives the round trip exactly, list fields included`() {
        assertEquals(proposal, proposal.toEntity().toDomain())
    }

    @Test
    fun `a row from a newer build with an unknown kind or status is skipped`() {
        assertNull(proposal.toEntity().copy(kind = "TIME_TRAVEL").toDomain())
        assertNull(proposal.toEntity().copy(status = "ARCHIVED").toDomain())
    }

    @Test
    fun `corrupt list columns decode to empty lists instead of crashing`() {
        val decoded = proposal.toEntity().copy(acceptanceCriteria = "not json", reviewFindings = "[1,").toDomain()!!
        assertEquals(emptyList<String>(), decoded.acceptanceCriteria)
        assertEquals(emptyList<String>(), decoded.reviewFindings)
    }

    @Test
    fun `a module version round-trips with its grants`() {
        val v = EvolutionModuleVersion("v1", "evo-x", "p1", "1.0.0", "{}", "cd".repeat(32), setOf("network", "data.read"), 5L, true)
        assertEquals(v, v.toEntity().toDomain())
        assertEquals("data.read,network", v.toEntity().grantedPermissions)
        assertEquals(emptySet<String>(), v.copy(grantedPermissions = emptySet()).toEntity().toDomain().grantedPermissions)
    }
}
