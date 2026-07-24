package com.hermes.agent.ui.settings

import androidx.lifecycle.ViewModel
import com.hermes.agent.data.proactive.BudgetStateStore
import com.hermes.agent.data.proactive.NotificationCaptureStore
import com.hermes.agent.data.proactive.ProactiveScheduler
import com.hermes.agent.domain.proactive.AnnoyanceBudget
import com.hermes.agent.domain.proactive.ProactiveSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class ProactiveSourceRow(
    val source: ProactiveSource,
    val consented: Boolean,
    val muted: Boolean,
)

data class ProactiveSettingsState(
    val sources: List<ProactiveSourceRow> = emptyList(),
    val dailyCap: Int = AnnoyanceBudget.DEFAULT_DAILY_CAP,
    val quietLabel: String = "",
    val notificationCapture: Boolean = false,
)

@HiltViewModel
class ProactiveSettingsViewModel @Inject constructor(
    private val store: BudgetStateStore,
    private val scheduler: ProactiveScheduler,
    private val captureStore: NotificationCaptureStore,
) : ViewModel() {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ProactiveSettingsState> = _state

    fun setConsent(source: ProactiveSource, granted: Boolean) {
        // Consent and its worker schedule move together (L-005).
        scheduler.setConsent(source, granted)
        _state.value = load()
    }

    fun unmute(source: ProactiveSource) {
        store.resetLessOfThis(source)
        _state.value = load()
    }

    fun setNotificationCapture(enabled: Boolean) {
        captureStore.captureEnabled = enabled
        if (!enabled) captureStore.clear() // consent revoked → captured data goes too
        _state.value = load()
    }

    private fun load(): ProactiveSettingsState {
        val budget = AnnoyanceBudget(store)
        return ProactiveSettingsState(
            sources = ProactiveSource.entries.map { source ->
                ProactiveSourceRow(
                    source = source,
                    consented = store.consent(source),
                    muted = budget.sourceAllowance(source) == 0,
                )
            },
            dailyCap = store.dailyCap,
            quietLabel = "${store.quietStart}–${store.quietEnd}",
            notificationCapture = captureStore.captureEnabled,
        )
    }
}
