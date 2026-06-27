package com.hermes.agent.di

import com.hermes.agent.data.repository.KanbanRepositoryImpl
import com.hermes.agent.domain.repository.KanbanRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class KanbanModule {
    @Binds
    @Singleton
    abstract fun bindKanbanRepository(impl: KanbanRepositoryImpl): KanbanRepository
}
