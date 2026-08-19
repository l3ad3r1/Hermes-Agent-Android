package com.hermes.agent.di

import com.hermes.agent.data.tool.ToolRegistryImpl
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton

/**
 * Multibound tool wiring.
 *
 * Each tool binds itself via `@Binds @IntoSet` in its own module.
 * [ToolRegistryImpl] receives the injected `Set<Tool>` and populates the registry.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ToolsModule {

    @Binds
    @Singleton
    abstract fun bindToolRegistry(impl: ToolRegistryImpl): ToolRegistry

    @Multibinds
    abstract fun bindTools(): Set<Tool>
}

