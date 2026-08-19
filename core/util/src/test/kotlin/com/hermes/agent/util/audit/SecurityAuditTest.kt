package com.hermes.agent.util.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAuditTest {

    @Test
    fun `all controls are non-empty`() {
        SecurityAudit.all.forEach { control ->
            assertTrue("title should not be blank: $control", control.title.isNotBlank())
            assertTrue("description should not be blank: $control", control.description.isNotBlank())
        }
    }

    @Test
    fun `enforced plus partial plus pending equals total`() {
        val total = SecurityAudit.all.size
        val sum = SecurityAudit.enforcedCount + SecurityAudit.partialCount + SecurityAudit.pendingCount
        assertEquals(total, sum)
    }

    @Test
    fun `every status is one of the three enum values`() {
        SecurityAudit.all.forEach { control ->
            assertTrue(
                "unexpected status: ${control.status}",
                control.status in setOf(ControlStatus.ENFORCED, ControlStatus.PARTIAL, ControlStatus.PENDING),
            )
        }
    }

    @Test
    fun `at least one control is ENFORCED`() {
        assertTrue("expected at least one ENFORCED control", SecurityAudit.enforcedCount > 0)
    }

    @Test
    fun `controls with known open gaps are not represented as enforced`() {
        assertEquals(ControlStatus.PARTIAL, SecurityControl.NO_UNTRUSTED_CODE.status)
        assertEquals(ControlStatus.PARTIAL, SecurityControl.TOOL_CONFIRMATION_GATE.status)
        assertEquals(ControlStatus.PARTIAL, SecurityControl.RAG_CONTENT_ISOLATION.status)
    }
}
