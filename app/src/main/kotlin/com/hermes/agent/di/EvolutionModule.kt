package com.hermes.agent.di

import com.hermes.agent.data.evolution.AppEvidenceSanitizer
import com.hermes.agent.data.evolution.EvolutionNotifier
import com.hermes.agent.data.evolution.GatewayEvolutionBotGateway
import com.hermes.agent.data.evolution.RoomEvolutionStore
import com.hermes.agent.data.evolution.RoutedEvolutionLlm
import com.hermes.agent.data.local.dao.ScriptPluginDao
import com.hermes.agent.data.plugin.ScriptPluginRepository
import com.hermes.agent.data.plugin.evolution.EvolutionDispatcher
import com.hermes.agent.data.plugin.evolution.EvolutionModuleInstaller
import com.hermes.agent.data.plugin.evolution.EvolutionModuleVetter
import com.hermes.agent.data.plugin.evolution.FeatureEvolutionAnalyzer
import com.hermes.agent.data.plugin.evolution.ModuleSmokeTestRunner
import com.hermes.agent.data.plugin.evolution.ToolOverrideController
import com.hermes.agent.data.plugin.evolution.UsageSignalMiner
import com.hermes.agent.data.plugin.evolution.builtInDescriptor
import com.hermes.agent.data.plugin.evolution.moduleOwning
import com.hermes.agent.domain.tool.ToolRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Feature evolution: the engine lives in agent-core (`data.plugin.evolution`),
 * and is assembled here with this app's storage, gateway, model routing,
 * redaction and notifications. Engine classes take plain constructors so the
 * same wiring can be copied into Jeeves (see docs/FEATURE-EVOLUTION.md).
 */
@Module
@InstallIn(SingletonComponent::class)
object EvolutionModule {

    @Provides
    @Singleton
    fun provideVetter(registry: ToolRegistry): EvolutionModuleVetter =
        EvolutionModuleVetter(
            existingTool = { registry.builtInDescriptor(it) },
            moduleOwning = { registry.moduleOwning(it) },
        )

    @Provides
    @Singleton
    fun provideSmokeRunner(): ModuleSmokeTestRunner = ModuleSmokeTestRunner()

    @Provides
    @Singleton
    fun provideAnalyzer(
        store: RoomEvolutionStore,
        llm: RoutedEvolutionLlm,
        registry: ToolRegistry,
        notifier: EvolutionNotifier,
    ): FeatureEvolutionAnalyzer = FeatureEvolutionAnalyzer(
        snapshots = store,
        miner = UsageSignalMiner(AppEvidenceSanitizer),
        llm = llm,
        store = store,
        builtInTools = { store.builtInToolNames() },
        events = notifier,
        appName = "Hermes",
    )

    @Provides
    @Singleton
    fun provideDispatcher(
        store: RoomEvolutionStore,
        gateway: GatewayEvolutionBotGateway,
        vetter: EvolutionModuleVetter,
        smokeRunner: ModuleSmokeTestRunner,
        notifier: EvolutionNotifier,
        registry: ToolRegistry,
    ): EvolutionDispatcher = EvolutionDispatcher(
        store = store,
        gateway = gateway,
        vetter = vetter,
        smokeRunner = smokeRunner,
        sanitizer = AppEvidenceSanitizer,
        events = notifier,
        existingTool = { registry.builtInDescriptor(it) },
    )

    @Provides
    @Singleton
    fun provideInstaller(
        store: RoomEvolutionStore,
        repository: ScriptPluginRepository,
        vetter: EvolutionModuleVetter,
        smokeRunner: ModuleSmokeTestRunner,
        notifier: EvolutionNotifier,
    ): EvolutionModuleInstaller = EvolutionModuleInstaller(
        store = store,
        versions = store,
        repository = repository,
        vetter = vetter,
        smokeRunner = smokeRunner,
        events = notifier,
    )

    @Provides
    @Singleton
    fun provideOverrideController(
        registry: ToolRegistry,
        repository: ScriptPluginRepository,
        dao: ScriptPluginDao,
        installer: EvolutionModuleInstaller,
    ): ToolOverrideController = ToolOverrideController(
        registry = registry,
        repository = repository,
        dao = dao,
        listener = installer,
        // Outlives any screen: reverts must happen even with the app in the background.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}
