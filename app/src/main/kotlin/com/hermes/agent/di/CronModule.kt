package com.hermes.agent.di

import android.content.Context
import androidx.work.WorkManager
import com.hermes.agent.data.repository.CronRepositoryImpl
import com.hermes.agent.domain.repository.CronRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CronModule {

    @Binds
    @Singleton
    abstract fun bindCronRepository(impl: CronRepositoryImpl): CronRepository
}

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
