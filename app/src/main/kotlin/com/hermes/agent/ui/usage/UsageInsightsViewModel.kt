package com.hermes.agent.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.repository.UsageInsightsRepository
import com.hermes.agent.domain.usage.UsageSummary
import com.hermes.agent.domain.usage.UsageTimeWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class UsageInsightsUiState(
    val window: UsageTimeWindow = UsageTimeWindow.LAST_7_DAYS,
    val summary: UsageSummary? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * Screen-side state for usage and cost insights.
 *
 * The numbers were previously reachable only by asking the agent to call the
 * `usage_insights` tool, which meant spending tokens to find out how many tokens
 * had been spent. Same repository, same aggregation - just rendered.
 */
@HiltViewModel
class UsageInsightsViewModel @Inject constructor(
    private val repository: UsageInsightsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UsageInsightsUiState())
    val state: StateFlow<UsageInsightsUiState> = _state.asStateFlow()

    init {
        load(UsageTimeWindow.LAST_7_DAYS)
    }

    fun load(window: UsageTimeWindow) = viewModelScope.launch {
        _state.value = _state.value.copy(window = window, isLoading = true, error = null)
        runCatching { repository.getUsageSummary(window) }
            .onSuccess { summary ->
                _state.value = _state.value.copy(summary = summary, isLoading = false)
            }
            .onFailure { e ->
                Timber.tag("Usage").w(e, "could not load usage summary")
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Could not read usage data.",
                )
            }
    }
}
