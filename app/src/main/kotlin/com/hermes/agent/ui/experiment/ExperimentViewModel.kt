package com.hermes.agent.ui.experiment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmStreamChunk
import com.hermes.agent.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExperimentState(
    val prompt: String = "",
    val modelA: String = "",
    val modelB: String = "",
    val responseA: String = "",
    val responseB: String = "",
    val isRunningA: Boolean = false,
    val isRunningB: Boolean = false,
    val errorA: String? = null,
    val errorB: String? = null,
)

@HiltViewModel
class ExperimentViewModel @Inject constructor(
    private val cloudLlmProvider: CloudLlmProvider,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ExperimentState())
    val state: StateFlow<ExperimentState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.observe().first()
            _state.value = _state.value.copy(
                modelA = settings.cloudModel,
                modelB = settings.cloudModel,
            )
        }
    }

    fun setPrompt(p: String) { _state.value = _state.value.copy(prompt = p) }
    fun setModelA(m: String) { _state.value = _state.value.copy(modelA = m) }
    fun setModelB(m: String) { _state.value = _state.value.copy(modelB = m) }

    fun run() {
        val prompt = _state.value.prompt.trim()
        if (prompt.isBlank()) return
        _state.value = _state.value.copy(
            responseA = "", responseB = "",
            isRunningA = true, isRunningB = true,
            errorA = null, errorB = null,
        )
        streamModel(prompt, isA = true)
        streamModel(prompt, isA = false)
    }

    private fun streamModel(prompt: String, isA: Boolean) = viewModelScope.launch {
        val messages = listOf(LlmMessage(role = "user", content = prompt))
        try {
            cloudLlmProvider.stream(messages).collect { chunk ->
                when (chunk) {
                    is LlmStreamChunk.Delta -> {
                        _state.value = if (isA) _state.value.copy(responseA = _state.value.responseA + chunk.text)
                                       else     _state.value.copy(responseB = _state.value.responseB + chunk.text)
                    }
                    is LlmStreamChunk.Error -> throw Exception(chunk.message)
                    else -> {}
                }
            }
            _state.value = if (isA) _state.value.copy(isRunningA = false)
                           else     _state.value.copy(isRunningB = false)
        } catch (e: Exception) {
            _state.value = if (isA) _state.value.copy(isRunningA = false, errorA = e.message)
                           else     _state.value.copy(isRunningB = false, errorB = e.message)
        }
    }
}
