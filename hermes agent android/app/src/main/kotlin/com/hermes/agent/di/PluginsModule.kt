package com.hermes.agent.di

import com.hermes.agent.data.plugin.GrpcPluginSandbox
import com.hermes.agent.data.plugin.HostPluginContext
import com.hermes.agent.data.plugin.InProcessPluginSandbox
import com.hermes.agent.data.plugin.PluginRegistryImpl
import com.hermes.agent.data.plugin.PluginResourceMonitor
import com.hermes.agent.data.plugins.ContactsPlugin
import com.hermes.agent.data.plugins.FileManagerPlugin
import com.hermes.agent.data.plugins.WeatherPlugin
import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 3 plugin wiring.
 *
 * Binds [PluginRegistry] to [PluginRegistryImpl] and constructs the three
 * first-party plugins at app startup. Each plugin is registered with the
 * registry via [PluginRegistryImpl.registerFirstParty] so the Plugins UI
 * shows them immediately in the INSTALLED state.
 *
 * Phase 3.x will add a discovery mechanism for third-party APK plugins
 * installed via the plugin marketplace.
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginsModule {

    @Provides
    @Singleton
    fun providePluginRegistry(
        inProcessSandbox: InProcessPluginSandbox,
        grpcSandbox: GrpcPluginSandbox,
        pluginContext: HostPluginContext,
        resourceMonitor: PluginResourceMonitor,
        weatherPlugin: WeatherPlugin,
        fileManagerPlugin: FileManagerPlugin,
        contactsPlugin: ContactsPlugin,
    ): PluginRegistry {
        val registry = PluginRegistryImpl(
            inProcessSandbox = inProcessSandbox,
            grpcSandbox = grpcSandbox,
            pluginContext = pluginContext,
            resourceMonitor = resourceMonitor,
        )
        // Register first-party plugins so they appear in the Plugins UI.
        listOf<Plugin>(weatherPlugin, fileManagerPlugin, contactsPlugin).forEach {
            registry.registerFirstParty(it)
        }
        return registry
    }

    @Provides
    @Singleton
    fun provideHostPluginContext(impl: HostPluginContext): PluginContext = impl
}
