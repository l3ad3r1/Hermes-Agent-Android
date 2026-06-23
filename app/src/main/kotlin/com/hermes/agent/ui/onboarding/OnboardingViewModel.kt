package com.hermes.agent.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.device.DeviceProfile
import com.hermes.agent.data.device.DeviceProfiler
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.domain.repository.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The details the setup journey collects to seed the agent's memory. */
data class SetupProfile(
    val name: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val wakeTime: String = "",
    val sleepTime: String = "",
    val notes: String = "",
)

/**
 * Drives the multi-step setup journey: welcome → profile → permissions → device
 * scan → finish. On finish, the collected profile and the scanned device
 * capabilities are committed to long-term [MemoryRepository] so the agent knows
 * who the user is and what the hardware can do.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val memory: MemoryRepository,
    private val deviceProfiler: DeviceProfiler,
) : ViewModel() {

    private val _step = MutableStateFlow(WELCOME)
    val step: StateFlow<Int> = _step.asStateFlow()

    private val _profile = MutableStateFlow(SetupProfile())
    val profile: StateFlow<SetupProfile> = _profile.asStateFlow()

    private val _device = MutableStateFlow<DeviceProfile?>(null)
    val device: StateFlow<DeviceProfile?> = _device.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _completed = MutableStateFlow(false)
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    fun next() = _step.update { (it + 1).coerceAtMost(DEVICE) }
    fun back() = _step.update { (it - 1).coerceAtLeast(WELCOME) }

    fun update(transform: (SetupProfile) -> SetupProfile) = _profile.update(transform)

    fun scanDevice() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            _device.value = runCatching { deviceProfiler.profile() }.getOrNull()
            _scanning.value = false
        }
    }

    /** Save everything to memory and mark onboarding complete. */
    fun finish() {
        viewModelScope.launch {
            saveToMemory()
            settings.setOnboardingCompleted(true)
            _completed.value = true
        }
    }

    /** Skip the remaining steps but still persist whatever was entered/scanned. */
    fun skip() = finish()

    private suspend fun saveToMemory() {
        val p = _profile.value
        val facts = buildList {
            if (p.name.isNotBlank()) add("The user's name is ${p.name}.")
            if (p.address.isNotBlank()) add("The user's home address is ${p.address}.")
            if (p.phone.isNotBlank()) add("The user's phone number is ${p.phone}.")
            if (p.email.isNotBlank()) add("The user's email address is ${p.email}.")
            if (p.wakeTime.isNotBlank() || p.sleepTime.isNotBlank()) {
                add(
                    "The user's daily schedule: wakes around ${p.wakeTime.ifBlank { "unspecified" }}, " +
                        "sleeps around ${p.sleepTime.ifBlank { "unspecified" }}. Avoid non-urgent " +
                        "notifications during their sleep hours.",
                )
            }
            if (p.notes.isNotBlank()) add("User note from setup: ${p.notes}")
        }
        facts.forEach { runCatching { memory.addMemory(it) } }
        _device.value?.let { runCatching { memory.addMemory(it.toMemoryText()) } }
    }

    companion object {
        const val WELCOME = 0
        const val PROFILE = 1
        const val PERMISSIONS = 2
        const val DEVICE = 3
    }
}
