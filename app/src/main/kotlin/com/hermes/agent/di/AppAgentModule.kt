package com.hermes.agent.di

import com.hermes.agent.data.appagent.AccessibilityAppAutomationGateway
import com.hermes.agent.data.appagent.AppAutomationGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Selects the installed app's AccessibilityService-backed automation driver. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppAgentModule {

    @Binds
    @Singleton
    abstract fun bindAppAutomationGateway(
        implementation: AccessibilityAppAutomationGateway,
    ): AppAutomationGateway
}
