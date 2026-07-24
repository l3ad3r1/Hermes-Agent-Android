package com.hermes.agent.domain.proactive

import java.time.LocalDate
import java.time.LocalTime

/** Why a proactive ping was allowed or suppressed. */
sealed interface BudgetVerdict {
    data object Admit : BudgetVerdict
    data class Deny(val reason: String) : BudgetVerdict
}

/** Persisted budget counters and preferences — see the data-layer store. */
interface BudgetState {
    fun consent(source: ProactiveSource): Boolean
    fun pingsToday(date: LocalDate): Int
    fun sourcePingsToday(source: ProactiveSource, date: LocalDate): Int
    fun lessOfThisCount(source: ProactiveSource): Int
    val dailyCap: Int
    val quietStart: LocalTime
    val quietEnd: LocalTime
}

/**
 * The annoyance budget (roadmap v0.12 — ships WITH the proactive engine, not
 * after it). Pure decision logic: every proactive ping must pass
 *  1. per-source consent (default off except time-based),
 *  2. Do-Not-Disturb (the phone's word beats ours),
 *  3. quiet hours,
 *  4. the hard daily cap across all sources,
 *  5. the per-source allowance, halved for each "less of this" press
 *     (2 → 1 → muted) so one tap durably matters.
 */
class AnnoyanceBudget(private val state: BudgetState) {

    fun evaluate(
        source: ProactiveSource,
        date: LocalDate,
        time: LocalTime,
        dndActive: Boolean,
    ): BudgetVerdict = when {
        !state.consent(source) ->
            BudgetVerdict.Deny("${source.displayName} pings are turned off")
        dndActive ->
            BudgetVerdict.Deny("Do Not Disturb is active")
        inQuietHours(time) ->
            BudgetVerdict.Deny("quiet hours (${state.quietStart}–${state.quietEnd})")
        state.pingsToday(date) >= state.dailyCap ->
            BudgetVerdict.Deny("daily ping budget (${state.dailyCap}) already spent")
        state.sourcePingsToday(source, date) >= sourceAllowance(source) ->
            BudgetVerdict.Deny(
                if (sourceAllowance(source) == 0) {
                    "${source.displayName} muted by \"less of this\""
                } else {
                    "${source.displayName} already used its ${sourceAllowance(source)} ping(s) today"
                },
            )
        else -> BudgetVerdict.Admit
    }

    /** Each "less of this" press halves the source's daily allowance: 2 → 1 → 0. */
    fun sourceAllowance(source: ProactiveSource): Int =
        DEFAULT_SOURCE_CAP shr state.lessOfThisCount(source).coerceAtMost(8)

    private fun inQuietHours(time: LocalTime): Boolean {
        val start = state.quietStart
        val end = state.quietEnd
        // A window like 22:00–07:00 wraps midnight; 13:00–14:00 does not.
        return if (start <= end) {
            time >= start && time < end
        } else {
            time >= start || time < end
        }
    }

    companion object {
        const val DEFAULT_DAILY_CAP = 4
        const val DEFAULT_SOURCE_CAP = 2
    }
}
