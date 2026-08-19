package com.hermes.agent.ui.experiment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmStreamChunk
import com.hermes.agent.data.llm.LocalLlmProvider
import com.hermes.agent.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelBenchmarkMetrics(
    val ttftMs: Long = 0L,
    val totalTimeMs: Long = 0L,
    val tokenCount: Int = 0,
    val tokensPerSec: Double = 0.0,
)

data class ExperimentState(
    val prompt: String = "",
    val modelA: String = "",
    val modelB: String = "",
    val responseA: String = "",
    val responseB: String = "",
    val metricsA: ModelBenchmarkMetrics = ModelBenchmarkMetrics(),
    val metricsB: ModelBenchmarkMetrics = ModelBenchmarkMetrics(),
    val isRunningA: Boolean = false,
    val isRunningB: Boolean = false,
    val errorA: String? = null,
    val errorB: String? = null,
)

@HiltViewModel
class ExperimentViewModel @Inject constructor(
    private val cloudLlmProvider: CloudLlmProvider,
    private val localLlmProvider: LocalLlmProvider,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ExperimentState())
    val state: StateFlow<ExperimentState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.observe().first()
            _state.value = _state.value.copy(
                modelA = settings.cloudModel.ifBlank { "gpt-4o-mini" },
                modelB = if (settings.auxModel.isNotBlank()) settings.auxModel else "claude-3-5-sonnet",
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
            metricsA = ModelBenchmarkMetrics(),
            metricsB = ModelBenchmarkMetrics(),
            isRunningA = true, isRunningB = true,
            errorA = null, errorB = null,
        )
        streamModel(prompt, modelName = _state.value.modelA, isA = true)
        streamModel(prompt, modelName = _state.value.modelB, isA = false)
    }

    private fun streamModel(prompt: String, modelName: String, isA: Boolean) = viewModelScope.launch {
        val messages = listOf(LlmMessage(role = "user", content = prompt))
        val startTime = System.currentTimeMillis()
        var firstTokenTime: Long? = null
        var tokenCount = 0

        try {
            val streamFlow = if (modelName.equals("local", ignoreCase = true) || modelName.equals("llama", ignoreCase = true)) {
                localLlmProvider.stream(messages)
            } else {
                cloudLlmProvider.streamWithModelOverride(messages, modelName)
            }

            streamFlow.collect { chunk ->
                when (chunk) {
                    is LlmStreamChunk.Delta -> {
                        if (firstTokenTime == null) {
                            firstTokenTime = System.currentTimeMillis()
                        }
                        tokenCount++
                        val currentTotalTime = System.currentTimeMillis() - startTime
                        val ttft = (firstTokenTime ?: startTime) - startTime
                        val tps = if (currentTotalTime > 0) (tokenCount.toDouble() / (currentTotalTime.toDouble() / 1000.0)) else 0.0

                        _state.value = if (isA) {
                            _state.value.copy(
                                responseA = _state.value.responseA + chunk.text,
                                metricsA = ModelBenchmarkMetrics(
                                    ttftMs = ttft,
                                    totalTimeMs = currentTotalTime,
                                    tokenCount = tokenCount,
                                    tokensPerSec = tps,
                                ),
                            )
                        } else {
                            _state.value.copy(
                                responseB = _state.value.responseB + chunk.text,
                                metricsB = ModelBenchmarkMetrics(
                                    ttftMs = ttft,
                                    totalTimeMs = currentTotalTime,
                                    tokenCount = tokenCount,
                                    tokensPerSec = tps,
                                ),
                            )
                        }
                    }
                    is LlmStreamChunk.Error -> throw Exception(chunk.message)
                    else -> {}
                }
            }

            val finalTotalTime = System.currentTimeMillis() - startTime
            val ttft = (firstTokenTime ?: startTime) - startTime
            val tps = if (finalTotalTime > 0) (tokenCount.toDouble() / (finalTotalTime.toDouble() / 1000.0)) else 0.0

            _state.value = if (isA) {
                _state.value.copy(
                    isRunningA = false,
                    metricsA = _state.value.metricsA.copy(
                        ttftMs = ttft,
                        totalTimeMs = finalTotalTime,
                        tokenCount = tokenCount,
                        tokensPerSec = tps,
                    ),
                )
            } else {
                _state.value.copy(
                    isRunningB = false,
                    metricsB = _state.value.metricsB.copy(
                        ttftMs = ttft,
                        totalTimeMs = finalTotalTime,
                        tokenCount = tokenCount,
                        tokensPerSec = tps,
                    ),
                )
            }
        } catch (e: Exception) {
            _state.value = if (isA) _state.value.copy(isRunningA = false, errorA = e.message)
                           else     _state.value.copy(isRunningB = false, errorB = e.message)
        }
    }
}
