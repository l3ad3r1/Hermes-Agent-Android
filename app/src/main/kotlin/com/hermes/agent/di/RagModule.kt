package com.hermes.agent.di

import com.hermes.agent.data.rag.RagPipelineImpl
import com.hermes.agent.domain.rag.RagPipeline
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 2 RAG wiring. Binds [RagPipeline] to [RagPipelineImpl].
 *
 * The implementation is already @Singleton @Inject-annotated on its
 * constructor — it pulls in DocumentDao, DocumentChunkDao,
 * EmbeddingService, VectorStore, and DispatcherProvider transitively.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RagModule {

    @Binds
    @Singleton
    abstract fun bindRagPipeline(impl: RagPipelineImpl): RagPipeline
}
