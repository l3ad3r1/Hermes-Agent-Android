package com.hermes.agent.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.ledger.ActivityLedger
import com.hermes.agent.domain.model.ActivityEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LedgerViewModel @Inject constructor(
    ledger: ActivityLedger,
) : ViewModel() {

    val entries: StateFlow<List<ActivityEntry>> = ledger.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
