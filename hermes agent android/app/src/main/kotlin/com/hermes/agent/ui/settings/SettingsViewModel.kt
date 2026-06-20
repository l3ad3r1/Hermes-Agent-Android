package com.hermes.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.security.KeystoreManager
import com.hermes.agent.data.security.KnoxSecurityManager
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val knox: KnoxSecurityManager,
    private val keystore: KeystoreManager,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserSettings(),
        )

    val isKnoxAvailable: Boolean get() = knox.isKnoxAvailable

    fun setOnDeviceEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setOnDeviceEnabled(enabled)
    }

    fun setCloudEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setCloudEnabled(enabled)
    }

    fun setCloudApiKey(key: String) = viewModelScope.launch {
        settingsRepository.setCloudApiKey(key)
    }

    fun setCloudBaseUrl(url: String) = viewModelScope.launch {
        settingsRepository.setCloudBaseUrl(url)
    }

    fun setCloudModel(model: String) = viewModelScope.launch {
        settingsRepository.setCloudModel(model)
    }

    fun setComplexityThreshold(value: Float) = viewModelScope.launch {
        settingsRepository.setComplexityThreshold(value)
    }

    fun setIdleUnloadMinutes(minutes: Int) = viewModelScope.launch {
        settingsRepository.setIdleUnloadMinutes(minutes)
    }

    /** Probe the keystore by ensuring the cloud-api-key alias exists. */
    fun probeKeystore(onResult: (Boolean) -> Unit) = viewModelScope.launch {
        runCatching {
            keystore.ensureKey(KeystoreManager.ALIAS_CLOUD_API_KEY)
            true
        }.onSuccess(onResult).onFailure { onResult(false) }
    }
}
