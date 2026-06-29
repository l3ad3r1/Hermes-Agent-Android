package com.hermes.agent.ui.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.memory.LearningState
import com.hermes.agent.data.memory.UserModelService
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.domain.repository.SkillRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Surfaces the self-improvement loop's state so the user can verify it at a
 * glance — and correct it: facts can be edited/deleted, and the user-model
 * rebuild can be triggered on demand.
 */
@HiltViewModel
class LearningViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    skillRepository: SkillRepository,
    private val userModelService: UserModelService,
    private val learningState: LearningState,
) : ViewModel() {

    private data class Counters(val count: Int = 0, val rebuiltAt: Int = 0)

    private val counters = MutableStateFlow(Counters())

    private val _rebuilding = MutableStateFlow(false)
    val rebuilding: StateFlow<Boolean> = _rebuilding.asStateFlow()

    val uiState: StateFlow<LearningUiState> = combine(
        memoryRepository.observeMemories(),
        skillRepository.observe(),
        counters,
    ) { memories, skills, c ->
        val facts = memories
            .filter { !it.content.startsWith(UserModelService.MODEL_PREFIX) }
            .sortedByDescending { it.createdAt }
        val userModel = memories
            .firstOrNull { it.content.startsWith(UserModelService.MODEL_PREFIX) }
            ?.content?.removePrefix(UserModelService.MODEL_PREFIX)?.trim()
        val autoSkills = skills
            .filter { !it.isBuiltIn && it.content.contains("author: hermes-auto") }
            .sortedByDescending { it.updatedAt }
            .map { it.name }

        LearningUiState(
            factCount = facts.size,
            recentFacts = facts.take(8).map { FactItem(it.id, it.content) },
            userModel = userModel,
            autoSkills = autoSkills,
            conversationCount = c.count,
            conversationsUntilRebuild =
                (UserModelService.UPDATE_EVERY_N - (c.count - c.rebuiltAt))
                    .coerceIn(0, UserModelService.UPDATE_EVERY_N),
            rebuildEvery = UserModelService.UPDATE_EVERY_N,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LearningUiState())

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        counters.value = Counters(
            count = runCatching { learningState.conversationCount() }.getOrDefault(0),
            rebuiltAt = runCatching { learningState.userModelRebuiltAt() }.getOrDefault(0),
        )
    }

    fun deleteFact(id: String) = viewModelScope.launch {
        runCatching { memoryRepository.deleteMemory(id) }
    }

    /** Replace a fact's text (no in-place update API, so add new + delete old). */
    fun editFact(id: String, newContent: String) = viewModelScope.launch {
        val trimmed = newContent.trim()
        if (trimmed.isEmpty()) {
            runCatching { memoryRepository.deleteMemory(id) }
            return@launch
        }
        runCatching {
            memoryRepository.addMemory(trimmed)
            memoryRepository.deleteMemory(id)
        }
    }

    /** Force a user-model rebuild now (for testing the loop). */
    fun rebuildUserModelNow() = viewModelScope.launch {
        if (_rebuilding.value) return@launch
        _rebuilding.value = true
        runCatching { userModelService.forceRebuild() }
        refresh()
        _rebuilding.value = false
    }
}

data class FactItem(val id: String, val content: String)

data class LearningUiState(
    val factCount: Int = 0,
    val recentFacts: List<FactItem> = emptyList(),
    val userModel: String? = null,
    val autoSkills: List<String> = emptyList(),
    val conversationCount: Int = 0,
    val conversationsUntilRebuild: Int = 0,
    val rebuildEvery: Int = 5,
)
