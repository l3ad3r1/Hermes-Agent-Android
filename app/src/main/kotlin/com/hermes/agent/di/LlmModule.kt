package com.hermes.agent.di

import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.CloudModelSource
import com.hermes.agent.data.llm.LlmProvider
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.HybridLlmRouter
import com.hermes.agent.data.remote.OpenAiApi
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.data.repository.ChatRepositoryImpl
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.data.repository.ConversationRepositoryImpl
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.data.repository.MemoryRepositoryImpl
import com.hermes.agent.util.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindMemoryRepository(impl: MemoryRepositoryImpl): MemoryRepository

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindLlmRouter(impl: HybridLlmRouter): LlmRouter

    @Binds
    @Singleton
    abstract fun bindCloudLlmProvider(impl: CloudLlmProvider): LlmProvider

    companion object {

        /**
         * Default (unqualified) [CloudModelSource]. Every bare
         * [CloudLlmProvider] injection — the orchestrator-facing primary
         * provider and all direct consumers — resolves to PRIMARY, i.e. the
         * [com.hermes.agent.data.settings.UserSettings.cloudModel].
         */
        @Provides
        fun provideCloudModelSource(): CloudModelSource = CloudModelSource.PRIMARY

        /**
         * Specialised cloud provider bound to the [auxModel] setting. Shares
         * the same API key / base URL as the primary; only the model id
         * differs. The [HybridLlmRouter] selects this for tasks it routes to
         * the secondary model.
         */
        @Provides
        @Singleton
        @Named("cloudAux")
        fun provideAuxCloudLlmProvider(
            api: OpenAiApi,
            settings: SettingsRepository,
            dispatchers: DispatcherProvider,
            json: Json,
        ): CloudLlmProvider =
            CloudLlmProvider(api, settings, dispatchers, json, CloudModelSource.AUX)
    }
}
