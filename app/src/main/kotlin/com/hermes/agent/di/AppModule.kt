package com.hermes.agent.di

import com.hermes.agent.data.security.EncryptedSettingsRepository
import com.hermes.agent.data.security.KeystoreManager
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.SettingsRepositoryImpl
import com.hermes.agent.util.DefaultDispatcherProvider
import com.hermes.agent.util.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * App-wide singleton bindings.
 *
 * Phase 4 update: the [SettingsRepository] binding now points at
 * [EncryptedSettingsRepository], which wraps [SettingsRepositoryImpl]
 * (the DataStore-backed implementation) so the cloud API key is
 * transparently encrypted at rest via [KeystoreManager] on every read
 * and write. Existing consumers of [SettingsRepository] are unaware
 * of the swap.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: EncryptedSettingsRepository): SettingsRepository
}

/**
 * Provides the underlying DataStore-backed [SettingsRepositoryImpl] to
 * [EncryptedSettingsRepository]. Qualified with [PlainSettings] so it
 * doesn't collide with the encrypted binding in [AppModule].
 */
@Module
@InstallIn(SingletonComponent::class)
object PlainSettingsModule {

    @Provides
    @Singleton
    @PlainSettings
    fun providePlainSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository = impl
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlainSettings
