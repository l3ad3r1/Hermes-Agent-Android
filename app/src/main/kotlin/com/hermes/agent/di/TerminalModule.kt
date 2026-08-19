package com.hermes.agent.di
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.settings.CloudProviderProfile

import com.hermes.agent.data.terminal.SshTerminalBackend
import com.hermes.agent.domain.terminal.RemoteTerminalBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TerminalModule {

    @Binds
    @Singleton
    abstract fun bindRemoteTerminalBackend(impl: SshTerminalBackend): RemoteTerminalBackend
}
