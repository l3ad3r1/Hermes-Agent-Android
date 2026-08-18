package com.hermes.agent.di

import com.hermes.agent.data.memory.EmbeddingService
import com.hermes.agent.data.memory.InMemoryVectorStore
import com.hermes.agent.data.memory.MiniLmEmbeddingService
import com.hermes.agent.data.memory.VectorStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 2 memory-subsystem wiring.
 *
 * Binds the [EmbeddingService] and [VectorStore] (in-memory brute-force ANN)
 * into the Hilt graph.
 *
 * Phase 3: [EmbeddingService] → on-device all-MiniLM-L6-v2 via ONNX Runtime
 * ([MiniLmEmbeddingService]), which transparently falls back to the
 * deterministic hashing mock until the model is downloaded.
 *
 * The public contracts stay identical — no consumer of these bindings
 * needs to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryModule {

    @Binds
    @Singleton
    abstract fun bindEmbeddingService(impl: MiniLmEmbeddingService): EmbeddingService

    @Binds
    @Singleton
    abstract fun bindVectorStore(impl: InMemoryVectorStore): VectorStore
}
