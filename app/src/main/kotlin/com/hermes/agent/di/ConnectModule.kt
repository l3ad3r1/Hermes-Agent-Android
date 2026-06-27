package com.hermes.agent.di

import com.hermes.agent.data.repository.ConnectorRepositoryImpl
import com.hermes.agent.domain.repository.ConnectorRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectModule {
    @Binds @Singleton
    abstract fun bindConnectorRepository(impl: ConnectorRepositoryImpl): ConnectorRepository
}
