package com.hermes.agent.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 4 onboarding state.
 *
 * Per Section 8.2 of the plan: "a carefully designed onboarding
 * experience that progressively introduces agent capabilities through
 * guided scenarios."
 *
 * Three screens, shown once on first launch:
 *   1. Welcome — Hermes brand intro + value prop.
 *   2. Privacy — explains on-device-first architecture, what stays
 *      local, what (optionally) goes to the cloud.
 *   3. Permissions — requests RECORD_AUDIO (for voice input) and
 *      POST_NOTIFICATIONS (for the notification agent). User can
 *      skip; we re-request on first use.
 *
 * Completion is persisted to DataStore under `onboarding_completed_v1`
 * so we never re-show on subsequent launches.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    private val _completed = MutableStateFlow(false)
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    fun next() {
        _step.value = (_step.value + 1).coerceAtMost(LAST_STEP)
    }

    fun back() {
        _step.value = (_step.value - 1).coerceAtLeast(0)
    }

    /** Skip remaining screens and mark onboarding complete. */
    fun skip() {
        complete()
    }

    /** Mark onboarding finished — called from the last screen's "Get started" button. */
    fun complete() {
        viewModelScope.launch {
            settings.setOnboardingCompleted(true)
            _completed.value = true
        }
    }

    companion object {
        const val WELCOME = 0
        const val PRIVACY = 1
        const val PERMISSIONS = 2
        const val LAST_STEP = PERMISSIONS
    }
}
