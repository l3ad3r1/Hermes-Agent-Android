package com.hermes.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.hotfix.HotfixManager
import com.hermes.agent.data.hotfix.HotfixStatus
import com.hermes.agent.data.hotfix.PatchOffer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings → Updates: applying, restarting into and removing hot-fix patches. */
@HiltViewModel
class HotfixViewModel @Inject constructor(
    private val manager: HotfixManager,
) : ViewModel() {

    val status: StateFlow<HotfixStatus> = manager.status
    private var poll: Job? = null

    init {
        manager.refresh()
    }

    /** User tapped "Apply fix (restart required)". */
    fun apply(offer: PatchOffer) {
        viewModelScope.launch {
            manager.stage(offer)
            // Tinker prepares the patch in its :patch process and records the outcome in a file;
            // follow it until it is ready or failed (a few seconds to a minute on a large patch).
            watchPreparation()
        }
    }

    fun remove() {
        viewModelScope.launch { manager.removePatch() }
    }

    fun restartNow() = manager.restartNow()

    fun refresh() = manager.refresh()

    private fun watchPreparation() {
        poll?.cancel()
        poll = viewModelScope.launch {
            repeat(POLL_LIMIT) {
                if (!isActive) return@launch
                manager.refresh()
                val staged = manager.status.value.staged
                if (staged == null || staged.installed) return@launch
                delay(POLL_MS)
            }
        }
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val POLL_LIMIT = 180
    }
}
