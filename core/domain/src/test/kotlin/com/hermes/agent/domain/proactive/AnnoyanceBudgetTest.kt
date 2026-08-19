package com.hermes.agent.domain.proactive

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnoyanceBudgetTest {

    private class FakeState(
        val consents: MutableMap<ProactiveSource, Boolean> =
            ProactiveSource.entries.associateWith { it.defaultConsent }.toMutableMap(),
        var total: Int = 0,
        val perSource: MutableMap<ProactiveSource, Int> = mutableMapOf(),
        val less: MutableMap<ProactiveSource, Int> = mutableMapOf(),
        override val dailyCap: Int = AnnoyanceBudget.DEFAULT_DAILY_CAP,
        override val quietStart: LocalTime = LocalTime.of(22, 0),
        override val quietEnd: LocalTime = LocalTime.of(7, 0),
    ) : BudgetState {
        override fun consent(source: ProactiveSource) = consents.getValue(source)
        override fun pingsToday(date: LocalDate) = total
        override fun sourcePingsToday(source: ProactiveSource, date: LocalDate) =
            perSource.getOrDefault(source, 0)
        override fun lessOfThisCount(source: ProactiveSource) = less.getOrDefault(source, 0)
    }

    private val date: LocalDate = LocalDate.of(2026, 7, 16)
    private val noon: LocalTime = LocalTime.NOON

    private fun verdict(state: FakeState, source: ProactiveSource = ProactiveSource.SCHEDULED_TASK,
                        time: LocalTime = noon, dnd: Boolean = false) =
        AnnoyanceBudget(state).evaluate(source, date, time, dnd)

    @Test
    fun `time-based source is admitted by default`() {
        assertEquals(BudgetVerdict.Admit, verdict(FakeState()))
    }

    @Test
    fun `digest and nudges default to no consent`() {
        val state = FakeState()
        assertTrue(verdict(state, ProactiveSource.DIGEST) is BudgetVerdict.Deny)
        assertTrue(verdict(state, ProactiveSource.NUDGE) is BudgetVerdict.Deny)
    }

    @Test
    fun `dnd beats everything`() {
        val deny = verdict(FakeState(), dnd = true)
        assertTrue(deny is BudgetVerdict.Deny)
        assertTrue((deny as BudgetVerdict.Deny).reason.contains("Do Not Disturb"))
    }

    @Test
    fun `quiet hours wrap midnight`() {
        val state = FakeState()
        assertTrue(verdict(state, time = LocalTime.of(23, 30)) is BudgetVerdict.Deny)
        assertTrue(verdict(state, time = LocalTime.of(6, 59)) is BudgetVerdict.Deny)
        assertEquals(BudgetVerdict.Admit, verdict(state, time = LocalTime.of(7, 0)))
    }

    @Test
    fun `daily cap suppresses the fifth ping`() {
        val state = FakeState(total = AnnoyanceBudget.DEFAULT_DAILY_CAP)
        val deny = verdict(state)
        assertTrue(deny is BudgetVerdict.Deny)
        assertTrue((deny as BudgetVerdict.Deny).reason.contains("budget"))
    }

    @Test
    fun `per-source cap holds even under the daily cap`() {
        val state = FakeState(
            total = 2,
            perSource = mutableMapOf(ProactiveSource.SCHEDULED_TASK to 2),
        )
        assertTrue(verdict(state) is BudgetVerdict.Deny)
    }

    @Test
    fun `less-of-this halves the allowance then mutes`() {
        val once = FakeState(less = mutableMapOf(ProactiveSource.SCHEDULED_TASK to 1))
        assertEquals(1, AnnoyanceBudget(once).sourceAllowance(ProactiveSource.SCHEDULED_TASK))
        assertEquals(
            BudgetVerdict.Admit,
            verdict(once),
        )

        val muted = FakeState(less = mutableMapOf(ProactiveSource.SCHEDULED_TASK to 2))
        assertEquals(0, AnnoyanceBudget(muted).sourceAllowance(ProactiveSource.SCHEDULED_TASK))
        val deny = verdict(muted)
        assertTrue(deny is BudgetVerdict.Deny)
        assertTrue((deny as BudgetVerdict.Deny).reason.contains("muted"))
    }

    @Test
    fun `granting consent admits a defaulted-off source`() {
        val state = FakeState().apply { consents[ProactiveSource.DIGEST] = true }
        assertEquals(BudgetVerdict.Admit, verdict(state, ProactiveSource.DIGEST))
    }
}
