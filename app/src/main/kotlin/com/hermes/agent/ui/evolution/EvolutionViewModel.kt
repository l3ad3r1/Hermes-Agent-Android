package com.hermes.agent.ui.evolution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.evolution.EvolutionSettings
import com.hermes.agent.data.evolution.FeatureEvolutionScheduler
import com.hermes.agent.data.evolution.GatewayEvolutionBotGateway
import com.hermes.agent.data.evolution.RepairReporterAppChangeFiler
import com.hermes.agent.data.evolution.RoomEvolutionStore
import com.hermes.agent.data.plugin.evolution.EvolutionModuleInstaller
import com.hermes.agent.data.plugin.evolution.EvolutionProposal
import com.hermes.agent.data.plugin.evolution.FeatureEvolutionAnalyzer
import com.hermes.agent.data.plugin.evolution.ProposalStatus
import com.hermes.agent.data.plugin.evolution.ToolOverrideController
import com.hermes.agent.data.plugin.evolution.transition
import com.hermes.agent.data.remote.BotProfileStore
import com.hermes.agent.work.FeatureEvolutionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Screen model for Feature evolution: proposals and their lifecycle, the module
 * review before install, rollback, and the builder/reviewer bot settings.
 * Every state change goes through the engine, which enforces the lifecycle.
 */
@HiltViewModel
class EvolutionViewModel @Inject constructor(
    private val store: RoomEvolutionStore,
    private val analyzer: FeatureEvolutionAnalyzer,
    private val installer: EvolutionModuleInstaller,
    private val scheduler: FeatureEvolutionScheduler,
    private val settings: EvolutionSettings,
    private val filer: RepairReporterAppChangeFiler,
    private val gateway: GatewayEvolutionBotGateway,
    botProfiles: BotProfileStore,
    overrideController: ToolOverrideController,
) : ViewModel() {

    data class UiState(
        val busy: String? = null,
        val message: String? = null,
        val error: String? = null,
        val review: EvolutionModuleInstaller.ModuleReview? = null,
        val gatewayConfigured: Boolean = false,
        val repairConfigured: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val proposals: StateFlow<List<EvolutionProposal>> =
        store.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settingsState: StateFlow<EvolutionSettings.State> = settings.state
    val profiles: StateFlow<List<String>> = botProfiles.profiles
    val liveOverrides: StateFlow<Map<String, String>> = overrideController.overrides

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _ui.update { it.copy(gatewayConfigured = gateway.isConfigured(), repairConfigured = filer.isConfigured) }
        }
    }

    fun analyzeNow() = work("Analysing your recent usage…") {
        val outcome = analyzer.analyze(notify = false)
        val summary = FeatureEvolutionWorker.describe(outcome)
        settings.recordAnalysis(System.currentTimeMillis(), summary)
        if (outcome is FeatureEvolutionAnalyzer.Outcome.Failed) error(summary) else summary
    }

    /** Approve a proposal, or retry one that failed or was rolled back. */
    fun approve(id: String) = work(null) {
        store.transition(id, ProposalStatus.APPROVED, "Approved. Send it to the bots to build it.")
            ?: error("This proposal can no longer be approved")
        "Approved"
    }

    fun reject(id: String) = work(null) {
        store.transition(id, ProposalStatus.REJECTED, "Rejected by you") ?: error("This proposal can no longer be rejected")
        // A build in progress is stopped, not just discarded when it finishes.
        scheduler.cancelDispatch(id)
        "Rejected — it will not be proposed again for 90 days"
    }

    fun sendToBots(id: String) = work(null) {
        val s = settings.current()
        if (s.builderProfile.isBlank() || s.reviewerProfile.isBlank()) error("Pick a builder bot and a reviewer bot first")
        if (!gateway.isConfigured()) error("Connect the desktop gateway first (Settings → Connections)")
        scheduler.dispatch(id)
        "Sent to '${s.builderProfile}' (builder) and '${s.reviewerProfile}' (reviewer). This can take a while."
    }

    fun openReview(id: String) = work("Re-checking the module on this phone…") {
        val review = installer.review(id).getOrThrow()
        _ui.update { it.copy(review = review) }
        null
    }

    fun closeReview() = _ui.update { it.copy(review = null) }

    fun install(review: EvolutionModuleInstaller.ModuleReview) = work("Installing…") {
        val installed = installer.install(review.proposal.id, review.sha256).getOrThrow()
        _ui.update { it.copy(review = null) }
        installed.statusMessage
    }

    fun rollback(id: String) = work("Rolling back…") { installer.rollback(id).getOrThrow().statusMessage }

    fun fileAppChange(id: String) = work("Filing to the self-repair repo…") {
        installer.fileAppChange(id, filer).getOrThrow().statusMessage
    }

    fun markInstalled(id: String) = work(null) { installer.markAppChangeInstalled(id).getOrThrow().statusMessage }

    fun setWeekly(enabled: Boolean) {
        settings.setWeeklyAnalysis(enabled)
        scheduler.applyWeekly(enabled)
    }

    fun setBuilder(profile: String) = settings.setBuilderProfile(profile)
    fun setReviewer(profile: String) = settings.setReviewerProfile(profile)

    fun dismissMessage() = _ui.update { it.copy(message = null, error = null) }

    /** Runs [block] once at a time; its string result becomes the message, a failure the error. */
    private fun work(label: String?, block: suspend () -> String?) {
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = label ?: "Working…", message = null, error = null) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { msg -> _ui.update { it.copy(busy = null, message = msg) } }
                .onFailure { e -> _ui.update { it.copy(busy = null, error = e.message ?: "Something went wrong") } }
        }
    }
}
