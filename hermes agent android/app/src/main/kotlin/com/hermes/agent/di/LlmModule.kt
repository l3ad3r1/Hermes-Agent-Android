package com.hermes.agent.di

import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.LlmProvider
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.HybridLlmRouter
import com.hermes.agent.data.llm.OnDeviceLlmProvider
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.data.repository.ChatRepositoryImpl
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.data.repository.ConversationRepositoryImpl
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.data.repository.MemoryRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifiers used to disambiguate which [LlmProvider] is being injected.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OnDeviceLlm

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CloudLlm

/**
 * Binds the LLM providers, router, and chat façade into the Hilt graph.
 *
 * Note: the two providers ([OnDeviceLlmProvider], [CloudLlmProvider]) are
 * already @Singleton @Inject-annotated on their constructors; we expose
 * them under their qualifiers so [HybridLlmRouter] can request both.
 */
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
    @OnDeviceLlm
    @Singleton
    abstract fun bindOnDeviceLlmProvider(impl: OnDeviceLlmProvider): LlmProvider

    @Binds
    @CloudLlm
    @Singleton
    abstract fun bindCloudLlmProvider(impl: CloudLlmProvider): LlmProvider
}
