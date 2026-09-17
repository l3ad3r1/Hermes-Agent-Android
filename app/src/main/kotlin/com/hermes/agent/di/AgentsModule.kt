package com.hermes.agent.di
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.settings.CloudProviderProfile

import com.hermes.agent.data.agent.HeuristicIntentClassifier
import com.hermes.agent.data.agent.OrchestratorImpl
import com.hermes.agent.data.agent.RepeatedExecutionGuard
import com.hermes.agent.data.remote.RemoteOrchestrator
import com.hermes.agent.data.repository.ActivityLedgerImpl
import com.hermes.agent.data.repository.ExecutionPlanRepositoryImpl
import com.hermes.agent.domain.agent.AgentRouter
import com.hermes.agent.domain.agent.ExecutionGuard
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.ledger.ActivityLedger
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Singleton

/**
 * Phase 2 agent-orchestration wiring.
 *
 * Binds the [AgentRouter] (intent classifier) and [Orchestrator]
 * implementations into the Hilt graph. The five concrete Agent classes
 * are @Singleton @Inject-annotated on their constructors and are pulled
 * in transitively by [com.hermes.agent.data.agent.AgentRegistry].
 *
 * The [Orchestrator] binding is runtime-aware: when
 * [UserSettings.remoteGatewayEnabled] is set, the app delegates all agent
 * execution to a PC Hermes gateway via [RemoteOrchestrator]. The selection
 * is made once at graph creation (Hilt bindings are compile-time), so
 * toggling remote mode requires an app restart. This is documented in the
 * Settings UI.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AgentsModule {

    @Binds
    @Singleton
    abstract fun bindAgentRouter(impl: HeuristicIntentClassifier): AgentRouter

    @Binds
    @Singleton
    abstract fun bindExecutionGuard(impl: RepeatedExecutionGuard): ExecutionGuard

    @Binds
    @Singleton
    abstract fun bindExecutionPlanRepository(
        impl: ExecutionPlanRepositoryImpl,
    ): ExecutionPlanRepository

    @Binds
    @Singleton
    abstract fun bindActivityLedger(impl: ActivityLedgerImpl): ActivityLedger
}

/**
 * Provides the [Orchestrator] binding, selecting local vs remote based on
 * the current [UserSettings.remoteGatewayEnabled] value.
 *
 * This is a separate object module because [Provides] cannot live in an
 * abstract class that also has [Binds] methods.
 */
@Module
@InstallIn(SingletonComponent::class)
object OrchestratorProviderModule {

    @Provides
    @Singleton
    fun provideOrchestrator(
        localImpl: OrchestratorImpl,
        remoteImpl: RemoteOrchestrator,
        settings: SettingsRepository,
    ): Orchestrator = runBlocking {
        val enabled = settings.current().remoteGatewayEnabled
        if (enabled) {
            Timber.tag("DI").i("Remote gateway enabled — using RemoteOrchestrator")
            remoteImpl
        } else {
            localImpl
        }
    }
}
