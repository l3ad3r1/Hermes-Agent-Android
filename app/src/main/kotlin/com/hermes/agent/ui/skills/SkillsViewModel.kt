package com.hermes.agent.ui.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.repository.SkillRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SkillsUiState(
    val skills: List<Skill> = emptyList(),
    val selectedSkill: Skill? = null,
    val showAddDialog: Boolean = false,
    val showViewDialog: Boolean = false,
    val addName: String = "",
    val addDescription: String = "",
    val addContent: String = "",
    val addCategory: String = "general",
    val addTags: String = "",
    val error: String? = null,
)

@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val skillRepository: SkillRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            skillRepository.seedBuiltIn()
        }
        viewModelScope.launch {
            skillRepository.observe().collect { skills ->
                _state.value = _state.value.copy(skills = skills)
            }
        }
    }

    fun showAddDialog() { _state.value = _state.value.copy(showAddDialog = true, error = null) }
    fun hideAddDialog() {
        _state.value = _state.value.copy(
            showAddDialog = false,
            addName = "", addDescription = "", addContent = "", addCategory = "general", addTags = "",
        )
    }

    fun setAddName(v: String) { _state.value = _state.value.copy(addName = v) }
    fun setAddDescription(v: String) { _state.value = _state.value.copy(addDescription = v) }
    fun setAddContent(v: String) { _state.value = _state.value.copy(addContent = v) }
    fun setAddCategory(v: String) { _state.value = _state.value.copy(addCategory = v) }
    fun setAddTags(v: String) { _state.value = _state.value.copy(addTags = v) }

    fun saveSkill() {
        val s = _state.value
        val name = s.addName.trim()
        val desc = s.addDescription.trim()
        val content = s.addContent.trim()
        if (name.isBlank()) { _state.value = s.copy(error = "Name is required"); return }
        if (content.isBlank()) { _state.value = s.copy(error = "Content is required"); return }
        viewModelScope.launch {
            runCatching {
                skillRepository.upsert(
                    name = name,
                    description = desc,
                    content = content,
                    category = s.addCategory.trim().ifBlank { "general" },
                    tags = s.addTags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                )
            }.onSuccess { hideAddDialog() }
             .onFailure { e -> _state.value = _state.value.copy(error = e.message) }
        }
    }

    fun viewSkill(skill: Skill) {
        _state.value = _state.value.copy(selectedSkill = skill, showViewDialog = true)
    }

    fun hideViewDialog() {
        _state.value = _state.value.copy(showViewDialog = false, selectedSkill = null)
    }

    fun deleteSkill(skill: Skill) {
        if (skill.isBuiltIn) return
        viewModelScope.launch { skillRepository.delete(skill.id) }
    }
}
