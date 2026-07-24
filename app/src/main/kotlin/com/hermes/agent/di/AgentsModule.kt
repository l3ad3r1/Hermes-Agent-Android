package com.hermes.agent.di

import com.hermes.agent.data.agent.HeuristicIntentClassifier
import com.hermes.agent.data.agent.OrchestratorImpl
import com.hermes.agent.data.agent.RepeatedExecutionGuard
import com.hermes.agent.data.repository.ActivityLedgerImpl
import com.hermes.agent.data.repository.ExecutionPlanRepositoryImpl
import com.hermes.agent.domain.agent.AgentRouter
import com.hermes.agent.domain.agent.ExecutionGuard
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.ledger.ActivityLedger
import com.hermes.agent.domain.repository.ExecutionPlanRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 2 agent-orchestration wiring.
 *
 * Binds the [AgentRouter] (intent classifier) and [Orchestrator]
 * implementations into the Hilt graph. The five concrete Agent classes
 * are @Singleton @Inject-annotated on their constructors and are pulled
 * in transitively by [com.hermes.agent.data.agent.AgentRegistry].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AgentsModule {

    @Binds
    @Singleton
    abstract fun bindAgentRouter(impl: HeuristicIntentClassifier): AgentRouter

    @Binds
    @Singleton
    abstract fun bindOrchestrator(impl: OrchestratorImpl): Orchestrator

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
