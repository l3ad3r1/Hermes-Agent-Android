package com.hermes.agent.di
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.settings.CloudProviderProfile

import com.hermes.agent.data.repository.SkillRepositoryImpl
import com.hermes.agent.data.repository.SupplementalPromptRepositoryImpl
import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.domain.repository.SupplementalPromptRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SkillsModule {
    @Binds
    @Singleton
    abstract fun bindSkillRepository(impl: SkillRepositoryImpl): SkillRepository

    @Binds
    @Singleton
    abstract fun bindSupplementalPromptRepository(
        impl: SupplementalPromptRepositoryImpl,
    ): SupplementalPromptRepository
}
