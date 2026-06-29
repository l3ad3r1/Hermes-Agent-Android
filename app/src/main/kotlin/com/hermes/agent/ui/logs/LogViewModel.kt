package com.hermes.agent.ui.logs

import androidx.lifecycle.ViewModel
import com.hermes.agent.data.log.LogManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(
    private val logManager: LogManager,
) : ViewModel() {

    private val _logs = MutableStateFlow("")
    val logs: StateFlow<String> = _logs.asStateFlow()

    init { refresh() }

    fun refresh() {
        _logs.value = logManager.readRecent()
    }

    fun clear() {
        logManager.clear()
        refresh()
    }
}
