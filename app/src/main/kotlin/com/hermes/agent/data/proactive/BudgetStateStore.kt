package com.hermes.agent.data.proactive

import android.content.Context
import android.content.SharedPreferences
import com.hermes.agent.domain.proactive.AnnoyanceBudget
import com.hermes.agent.domain.proactive.BudgetState
import com.hermes.agent.domain.proactive.ProactiveSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedPreferences-backed [BudgetState]. Counters are keyed by date so a new
 * day naturally starts fresh without a reset job; stale keys for previous
 * days are dropped on write.
 */
@Singleton
class BudgetStateStore @Inject constructor(
    @ApplicationContext context: Context,
) : BudgetState {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun consent(source: ProactiveSource): Boolean =
        prefs.getBoolean("consent_${source.name}", source.defaultConsent)

    fun setConsent(source: ProactiveSource, granted: Boolean) {
        prefs.edit().putBoolean("consent_${source.name}", granted).apply()
    }

    override fun pingsToday(date: LocalDate): Int =
        prefs.getInt("pings_$date", 0)

    override fun sourcePingsToday(source: ProactiveSource, date: LocalDate): Int =
        prefs.getInt("pings_${date}_${source.name}", 0)

    override fun lessOfThisCount(source: ProactiveSource): Int =
        prefs.getInt("less_${source.name}", 0)

    fun recordLessOfThis(source: ProactiveSource) {
        prefs.edit().putInt("less_${source.name}", lessOfThisCount(source) + 1).apply()
    }

    fun resetLessOfThis(source: ProactiveSource) {
        prefs.edit().remove("less_${source.name}").apply()
    }

    fun recordPing(source: ProactiveSource, date: LocalDate) {
        val editor = prefs.edit()
            .putInt("pings_$date", pingsToday(date) + 1)
            .putInt("pings_${date}_${source.name}", sourcePingsToday(source, date) + 1)
        // Drop counters from previous days so the file stays small.
        prefs.all.keys
            .filter { it.startsWith("pings_") && !it.contains(date.toString()) }
            .forEach(editor::remove)
        editor.apply()
    }

    fun setQuietHours(start: LocalTime, end: LocalTime) {
        prefs.edit()
            .putInt("quiet_start_sec", start.toSecondOfDay())
            .putInt("quiet_end_sec", end.toSecondOfDay())
            .apply()
    }

    override val dailyCap: Int
        get() = prefs.getInt("daily_cap", AnnoyanceBudget.DEFAULT_DAILY_CAP)

    override val quietStart: LocalTime
        get() = LocalTime.ofSecondOfDay(
            prefs.getInt("quiet_start_sec", DEFAULT_QUIET_START.toSecondOfDay()).toLong(),
        )

    override val quietEnd: LocalTime
        get() = LocalTime.ofSecondOfDay(
            prefs.getInt("quiet_end_sec", DEFAULT_QUIET_END.toSecondOfDay()).toLong(),
        )

    companion object {
        const val PREFS = "proactive_budget"
        val DEFAULT_QUIET_START: LocalTime = LocalTime.of(22, 0)
        val DEFAULT_QUIET_END: LocalTime = LocalTime.of(7, 0)
    }
}
