package com.hermes.agent.di

import com.hermes.agent.data.repository.MoodRepositoryImpl
import com.hermes.agent.domain.repository.MoodRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MoodModule {
    @Binds @Singleton
    abstract fun bindMoodRepository(impl: MoodRepositoryImpl): MoodRepository
}
