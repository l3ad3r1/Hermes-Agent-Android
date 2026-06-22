package com.hermes.agent.di

import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.LlmProvider
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.HybridLlmRouter
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
}
