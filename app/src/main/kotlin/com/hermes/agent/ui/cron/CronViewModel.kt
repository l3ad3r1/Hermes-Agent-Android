package com.hermes.agent.ui.cron
import com.hermes.agent.domain.settings.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.remote.BotProfileStore
import com.hermes.agent.data.remote.GatewayApiClient
import com.hermes.agent.data.remote.RemoteJob
import com.hermes.agent.domain.model.ScheduledTask
import com.hermes.agent.domain.repository.CronRepository
import com.hermes.agent.util.IdGenerator
import com.hermes.agent.work.CronScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A PC gateway job, tagged with the bot profile it belongs to (for cross-profile grouping). */
data class RemoteJobEntry(val profile: String, val job: RemoteJob) {
    val key: String get() = "$profile:${job.id}"
}

@HiltViewModel
class CronViewModel @Inject constructor(
    private val cronRepository: CronRepository,
    private val cronScheduler: CronScheduler,
    private val gateway: GatewayApiClient,
    private val profileStore: BotProfileStore,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val tasks: StateFlow<List<ScheduledTask>> = cronRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _remoteJobs = MutableStateFlow<List<RemoteJobEntry>>(emptyList())
    val remoteJobs: StateFlow<List<RemoteJobEntry>> = _remoteJobs.asStateFlow()

    private val _remoteConfigured = MutableStateFlow(false)
    val remoteConfigured: StateFlow<Boolean> = _remoteConfigured.asStateFlow()

    private val _remoteLoading = MutableStateFlow(false)
    val remoteLoading: StateFlow<Boolean> = _remoteLoading.asStateFlow()

    private val _remoteError = MutableStateFlow<String?>(null)
    val remoteError: StateFlow<String?> = _remoteError.asStateFlow()

    private val _busyRemoteKey = MutableStateFlow<String?>(null)
    val busyRemoteKey: StateFlow<String?> = _busyRemoteKey.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.observe()
                .map { it.remoteGatewayUrl.isNotBlank() && it.remoteGatewayApiKey.isNotBlank() }
                .distinctUntilChanged()
                .collect { configured ->
                    _remoteConfigured.value = configured
                    if (configured) refreshRemoteJobs()
                }
        }
    }

    /** Fetch every configured bot profile's jobs concurrently (the PC's side of "Scheduled
     * bots"). Every profile's failure is reported, without blanking the others' results. */
    fun refreshRemoteJobs() = viewModelScope.launch {
        if (!_remoteConfigured.value) return@launch
        _remoteLoading.value = true
        _remoteError.value = null
        val results = gateway.listJobsByProfile(profileStore.profiles.value)
        _remoteJobs.value = results.flatMap { (profile, result) ->
            result.getOrNull()?.map { RemoteJobEntry(profile, it) } ?: emptyList()
        }
        val errors = results.mapNotNull { (profile, result) -> result.exceptionOrNull()?.let { "$profile: ${it.message}" } }
        _remoteError.value = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
        _remoteLoading.value = false
    }

    /** Apply pause, resume or run to one PC job, then show its new state. */
    fun actOnRemoteJob(entry: RemoteJobEntry, action: String) = viewModelScope.launch {
        _busyRemoteKey.value = entry.key
        val profile = entry.profile.takeIf { it != BotProfileStore.DEFAULT }
        runCatching { gateway.jobAction(entry.job.id, action, profile) }
            .onSuccess { updated ->
                _remoteJobs.value = _remoteJobs.value.map { if (it.key == entry.key) it.copy(job = updated) else it }
            }
            .onFailure { e -> _remoteError.value = "$action failed: ${e.message}" }
        _busyRemoteKey.value = null
    }

    fun addTask(label: String, prompt: String, cronExpression: String) {
        val task = ScheduledTask(
            id = IdGenerator.newId(),
            label = label,
            prompt = prompt,
            cronExpression = cronExpression,
        )
        viewModelScope.launch {
            cronRepository.add(task)
            cronScheduler.schedule(task)
        }
    }

    fun toggle(taskId: String) {
        viewModelScope.launch {
            cronRepository.toggle(taskId)
            // Read back from the repository, not the UI StateFlow — `tasks` is
            // WhileSubscribed and may not have emitted yet, which would flip
            // the DB row but silently leave the WorkManager job unchanged.
            val toggled = cronRepository.observe().first().find { it.id == taskId } ?: return@launch
            if (toggled.isEnabled) cronScheduler.schedule(toggled) else cronScheduler.cancel(taskId)
        }
    }

    fun delete(taskId: String) {
        viewModelScope.launch {
            cronRepository.delete(taskId)
            cronScheduler.cancel(taskId)
        }
    }
}
