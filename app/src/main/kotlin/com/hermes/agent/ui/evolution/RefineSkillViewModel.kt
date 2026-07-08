package com.hermes.agent.ui.evolution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.evolution.ReflectiveSkillRefiner
import com.hermes.agent.domain.model.Skill
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

sealed class RefineUiState {
    object Idle : RefineUiState()
    data class Running(val skillName: String) : RefineUiState()
    data class Proposal(val proposal: ReflectiveSkillRefiner.Proposal) : RefineUiState()
    object Applied : RefineUiState()
    data class NoChange(val message: String) : RefineUiState()
    data class Error(val message: String) : RefineUiState()
}

@HiltViewModel
class RefineSkillViewModel @Inject constructor(
    private val refiner: ReflectiveSkillRefiner,
    skillRepository: SkillRepository,
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

    fun reset() {
        _state.value = RefineUiState.Idle
    }
}
