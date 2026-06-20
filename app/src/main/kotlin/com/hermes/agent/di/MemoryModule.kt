package com.hermes.agent.di

import com.hermes.agent.data.memory.EmbeddingService
import com.hermes.agent.data.memory.HashingEmbeddingService
import com.hermes.agent.data.memory.InMemoryVectorStore
import com.hermes.agent.data.memory.VectorStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 2 memory-subsystem wiring.
 *
 * Binds the [EmbeddingService] (hashing mock) and [VectorStore]
 * (in-memory brute-force ANN) into the Hilt graph.
 *
 * Phase 3 will replace both:
 *   - EmbeddingService → on-device all-MiniLM-L6-v2 via MLC-LLM / ONNX-RT.
 *   - VectorStore → SQLite-VSS backed by the `embedding` BLOB column.
 *
 * The public contracts stay identical — no consumer of these bindings
 * needs to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryModule {

    @Binds
    @Singleton
    abstract fun bindEmbeddingService(impl: HashingEmbeddingService): EmbeddingService

    @Binds
    @Singleton
    abstract fun bindVectorStore(impl: InMemoryVectorStore): VectorStore
}
