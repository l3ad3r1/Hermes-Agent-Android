package com.hermes.agent.data.evolution

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature-evolution preferences, kept on the device (not in backups' shared
 * settings) because the bot choices name profiles on this user's own PC.
 *
 * The weekly analysis is OFF by default: it sends anonymised usage evidence to a
 * cloud model, which is a choice the user makes. "Analyze now" works either way,
 * and nothing is ever built or installed without a separate approval.
 */
@Singleton
class EvolutionSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    data class State(
        val weeklyAnalysis: Boolean = false,
        val builderProfile: String = "",
        val reviewerProfile: String = "",
        val lastAnalysisAt: Long = 0L,
        val lastAnalysisResult: String = "",
    )

    private val prefs = context.getSharedPreferences("feature_evolution", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    fun current(): State = _state.value

    fun setWeeklyAnalysis(enabled: Boolean) = write { putBoolean(KEY_WEEKLY, enabled) }
    fun setBuilderProfile(profile: String) = write { putString(KEY_BUILDER, profile.trim()) }
    fun setReviewerProfile(profile: String) = write { putString(KEY_REVIEWER, profile.trim()) }

    fun recordAnalysis(at: Long, result: String) = write {
        putLong(KEY_LAST_AT, at)
        putString(KEY_LAST_RESULT, result.take(300))
    }

    private fun write(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    private fun read() = State(
        weeklyAnalysis = prefs.getBoolean(KEY_WEEKLY, false),
        builderProfile = prefs.getString(KEY_BUILDER, null).orEmpty(),
        reviewerProfile = prefs.getString(KEY_REVIEWER, null).orEmpty(),
        lastAnalysisAt = prefs.getLong(KEY_LAST_AT, 0L),
        lastAnalysisResult = prefs.getString(KEY_LAST_RESULT, null).orEmpty(),
    )

    private companion object {
        const val KEY_WEEKLY = "weekly_analysis"
        const val KEY_BUILDER = "builder_profile"
        const val KEY_REVIEWER = "reviewer_profile"
        const val KEY_LAST_AT = "last_analysis_at"
        const val KEY_LAST_RESULT = "last_analysis_result"
    }
}
