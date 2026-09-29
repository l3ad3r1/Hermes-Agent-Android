package com.hermes.agent.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRedactorTest {

    @Test
    fun `personal data and secrets are removed`() {
        val raw = """
            Failed for ren@example.com calling +91 98765 43210 and 9876543210
            Authorization: Bearer abcDEF123ghiJKL456mnoPQR789stuVWX012
            token=ghp_abcdefghijklmnopqrstuvwxyz0123
            host http://mfi-nb-0102.tail7dc9bd.ts.net:8643/v1 at 100.123.161.76 and 192.168.0.200
            GET https://api.example.com/v1/search?q=home+address&key=zzz
        """.trimIndent()

        val out = ReportRedactor.redact(raw)

        for (leak in listOf("ren@example.com", "98765", "9876543210", "abcDEF123", "ghp_", "mfi-nb-0102",
            "100.123.161.76", "192.168.0.200", "home+address")) {
            assertFalse("$leak survived:\n$out", out.contains(leak))
        }
        assertTrue(out, out.contains("https://api.example.com/v1/search?[query]"))
    }

    @Test
    fun `stack traces and timestamps stay readable`() {
        val trace = """
            09-28 22:58:17.093 W/ActivityLedger: ledger write failed
            Time: 2026-09-28 22:58:17 +0530
            java.lang.IllegalStateException: boom
            	at com.hermes.agent.data.llm.RoutedProviderChain${'$'}completeWithTools${'$'}2.invokeSuspend(RoutedProviderChain.kt:39)
            	at com.hermes.agent.data.agent.AgentLoopRunner.runWithinBudget(AgentLoopRunner.kt:117)
            Jeeves: 1.0.8-debug (106)
        """.trimIndent()

        assertEquals(trace, ReportRedactor.redact(trace))
    }
}
