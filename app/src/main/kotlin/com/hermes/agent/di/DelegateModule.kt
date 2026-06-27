package com.hermes.agent.di

import com.hermes.agent.data.repository.AgentTaskRepositoryImpl
import com.hermes.agent.domain.repository.AgentTaskRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DelegateModule {
    @Binds @Singleton
    abstract fun bindAgentTaskRepository(impl: AgentTaskRepositoryImpl): AgentTaskRepository
}
