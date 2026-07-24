package com.hermes.agent.di

import com.hermes.agent.data.jotter.JotterAiProviderImpl
import com.l3ad3r1.octojotter.domain.JotterAiProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class JotterAiModule {

    @Binds
    abstract fun bindJotterAiProvider(
        impl: JotterAiProviderImpl
    ): JotterAiProvider
}
