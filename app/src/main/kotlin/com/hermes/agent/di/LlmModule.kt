package com.hermes.agent.di

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.CloudModelCatalog
import com.hermes.agent.data.llm.OpenAiCloudModelCatalog
import com.hermes.agent.data.llm.CloudModelSource
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.HybridLlmRouter
import com.hermes.agent.data.llm.LlmRoutingPolicy
import com.hermes.agent.data.llm.QualityAwareLlmRoutingPolicy
import com.hermes.agent.data.llm.CloudProviderFactory
import com.hermes.agent.data.llm.CredentialPoolManager
import com.hermes.agent.data.llm.ProfileCloudProviderFactory
import com.hermes.agent.domain.product.ProductIdentity
import com.hermes.agent.data.remote.OpenAiApi
import com.hermes.agent.domain.settings.SettingsRepository
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
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {

    @Binds
    @Singleton
    abstract fun bindMemoryRepository(impl: MemoryRepositoryImpl): MemoryRepository

    @Binds
    @Singleton
    abstract fun bindLlmRouter(impl: HybridLlmRouter): LlmRouter

    @Binds
    @Singleton
    abstract fun bindLlmRoutingPolicy(impl: QualityAwareLlmRoutingPolicy): LlmRoutingPolicy

    @Binds
    @Singleton
    abstract fun bindProfileCloudProviderFactory(impl: CloudProviderFactory): ProfileCloudProviderFactory

    @Binds
    @Singleton
    abstract fun bindCloudModelCatalog(impl: OpenAiCloudModelCatalog): CloudModelCatalog

    @Binds
    @Singleton
    abstract fun bindCloudLlmProvider(impl: CloudLlmProvider): LlmProvider

    companion object {

        @Provides
        @Singleton
        fun provideProductIdentity(): ProductIdentity =
            ProductIdentity(displayName = "Hermes", notificationChannelId = "hermes_notify")

        @Provides
        @Singleton
        fun provideInferenceEngine(
            @ApplicationContext context: Context,
        ): InferenceEngine = AiChat.getInferenceEngine(context)

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
            productIdentity: ProductIdentity,
            credentialPool: CredentialPoolManager,
        ): CloudLlmProvider =
            CloudLlmProvider(
                api,
                settings,
                dispatchers,
                json,
                CloudModelSource.AUX,
                productIdentity,
                credentialPool,
            )
    }
}

/**
 * Provides the [ChatRepository] and [ConversationRepository] bindings,
 * selecting local vs remote based on the current
 * [UserSettings.remoteGatewayEnabled] value.
 *
 * This is a separate object module because [Provides] cannot live in an
 * abstract class that also has [Binds] methods. Like the [Orchestrator]
 * binding, the selection is made once at graph creation, so toggling
 * remote mode requires an app restart.
 */
@Module
@InstallIn(SingletonComponent::class)
object RemoteRepositoryProviderModule {

    @Provides
    @Singleton
    fun provideChatRepository(
        localImpl: ChatRepositoryImpl,
        remoteImpl: com.hermes.agent.data.remote.RemoteChatRepository,
        settings: SettingsRepository,
    ): ChatRepository = runBlocking {
        if (settings.current().remoteGatewayEnabled) remoteImpl else localImpl
    }

    @Provides
    @Singleton
    fun provideConversationRepository(
        localImpl: ConversationRepositoryImpl,
        remoteImpl: com.hermes.agent.data.remote.RemoteConversationRepository,
        settings: SettingsRepository,
    ): ConversationRepository = runBlocking {
        if (settings.current().remoteGatewayEnabled) remoteImpl else localImpl
    }

    /**
     * A [ChatRepository] that always runs on this phone's own model, regardless of
     * [UserSettings.remoteGatewayEnabled]. [ChatRepositoryImpl] itself always resolves the
     * `Orchestrator` binding — which [OrchestratorProviderModule] switches to
     * [com.hermes.agent.data.remote.RemoteOrchestrator] app-wide when the remote gateway is
     * on — so `localImpl` above is not actually local-only once that setting is enabled.
     * Bots that must run on-device (e.g. the Bots screen's local personas) need this instead.
     */
    @Provides
    @Singleton
    @Named("local")
    fun provideLocalOnlyChatRepository(
        conversationRepository: ConversationRepositoryImpl,
        memoryRepository: com.hermes.agent.domain.repository.MemoryRepository,
        router: LlmRouter,
        orchestratorImpl: com.hermes.agent.data.agent.OrchestratorImpl,
        compressor: com.hermes.agent.data.llm.ConversationCompressor,
        dispatchers: DispatcherProvider,
        reasoningStore: com.hermes.agent.data.chat.ReasoningStore,
    ): ChatRepository = ChatRepositoryImpl(
        conversationRepository,
        memoryRepository,
        router,
        orchestratorImpl,
        compressor,
        dispatchers,
        reasoningStore,
    )
}
