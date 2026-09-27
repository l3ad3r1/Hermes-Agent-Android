package com.hermes.agent.ui.evolution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.evolution.ReflectiveSkillRefiner
import com.hermes.agent.domain.repository.SkillRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SkillOption(val name: String, val description: String)

/** One archived version of a skill, flattened for display. */
data class RevisionRow(
    val id: String,
    val version: String,
    val note: String,
    val description: String,
    val replacedAt: Long,
)

data class HistoryState(
    val skillName: String,
    val revisions: List<RevisionRow>,
    val loading: Boolean,
)

sealed class RefineUiState {
    object Idle : RefineUiState()
    data class Running(val skillName: String) : RefineUiState()
    data class Proposal(val proposal: ReflectiveSkillRefiner.Proposal) : RefineUiState()
    object Applied : RefineUiState()
    data class Restored(val version: String) : RefineUiState()
    data class NoChange(val message: String) : RefineUiState()
    data class Error(val message: String) : RefineUiState()
}

@HiltViewModel
class RefineSkillViewModel @Inject constructor(
    private val refiner: ReflectiveSkillRefiner,
    private val skillRepository: SkillRepository,
) : ViewModel() {

    /** Only user-created skills are refinable (built-ins are read-only). */
    val skills: StateFlow<List<SkillOption>> = skillRepository.observe()
        .map { list ->
            list.filter { !it.isBuiltIn }
                .sortedByDescending { it.useCount }
                .map { SkillOption(it.name, it.description) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow<RefineUiState>(RefineUiState.Idle)
    val state: StateFlow<RefineUiState> = _state.asStateFlow()

    private val _history = MutableStateFlow<HistoryState?>(null)
    val history: StateFlow<HistoryState?> = _history.asStateFlow()

    fun refine(skillName: String) {
        if (_state.value is RefineUiState.Running) return
        _state.value = RefineUiState.Running(skillName)
        viewModelScope.launch {
            _state.value = when (val outcome = refiner.refine(skillName)) {
                is ReflectiveSkillRefiner.Outcome.Ready -> RefineUiState.Proposal(outcome.proposal)
                is ReflectiveSkillRefiner.Outcome.NoChange -> RefineUiState.NoChange(outcome.reason)
                is ReflectiveSkillRefiner.Outcome.Failed -> RefineUiState.Error(outcome.message)
            }
        }
    }

    fun apply(proposal: ReflectiveSkillRefiner.Proposal) {
        viewModelScope.launch {
            runCatching { refiner.apply(proposal) }
                .onSuccess { _state.value = RefineUiState.Applied }
                .onFailure { _state.value = RefineUiState.Error(it.message ?: "Failed to apply") }
        }
    }

    fun showHistory(skillName: String) {
        _history.value = HistoryState(skillName, emptyList(), loading = true)
        viewModelScope.launch {
            val rows = runCatching { skillRepository.revisions(skillName) }
                .getOrDefault(emptyList())
                .map {
                    RevisionRow(
                        id = it.id,
                        version = it.version,
                        note = it.note,
                        description = it.description,
                        replacedAt = it.replacedAt,
                    )
                }
            // Ignore a response that lost the race to a different skill's tap.
            if (_history.value?.skillName == skillName) {
                _history.value = HistoryState(skillName, rows, loading = false)
            }
        }
    }

    fun closeHistory() {
        _history.value = null
    }

    fun restore(revision: RevisionRow) {
        val open = _history.value ?: return
        viewModelScope.launch {
            runCatching { skillRepository.restore(revision.id) }
                .onSuccess { restored ->
                    if (restored == null) {
                        _state.value = RefineUiState.Error("That revision is no longer available.")
                        _history.value = null
                    } else {
                        _state.value = RefineUiState.Restored(revision.version)
                        // The restore archived the version it replaced, so the
                        // list the user is looking at is already out of date.
                        showHistory(open.skillName)
                    }
                }
                .onFailure { _state.value = RefineUiState.Error(it.message ?: "Restore failed") }
        }
    }

    fun reset() {
        _state.value = RefineUiState.Idle
    }
}
