package com.hermes.agent.data.llm

import com.hermes.agent.domain.tool.ToolDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/** Tries the selected cloud provider first, then a local provider on transport failure. */
internal class FailoverLlmProvider(
    private val primary: LlmProvider,
    private val fallback: LlmProvider,
) : LlmProvider {
    private val fallbackUsed = AtomicBoolean(false)

    override val name: String get() = activeProvider().name
    override val isOnDevice: Boolean get() = activeProvider().isOnDevice
    override val model: String get() = activeProvider().model

    override suspend fun complete(messages: List<LlmMessage>): LlmResponse =
        withFailover("completion") { provider -> provider.complete(messages) }

    override suspend fun completeWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): LlmToolResponse = withFailover("tool completion") { provider ->
        provider.completeWithTools(messages, tools)
    }

    override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> =
        streamWithFailover { provider -> provider.stream(messages) }

    override fun streamWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): Flow<LlmStreamChunk> = streamWithFailover { provider ->
        provider.streamWithTools(messages, tools)
    }

    override suspend fun isAvailable(): Boolean = primary.isAvailable() || fallback.isAvailable()

    private fun activeProvider(): LlmProvider = if (fallbackUsed.get()) fallback else primary

    private suspend fun <T> withFailover(
        operation: String,
        call: suspend (LlmProvider) -> T,
    ): T = if (fallbackUsed.get()) {
        call(fallback)
    } else try {
        call(primary)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        if (!failure.isTransientTransportFailure() || !fallbackAvailable()) throw failure
        Timber.tag("LlmFailover").w(failure, "Cloud %s failed; retrying with local model", operation)
        fallbackUsed.set(true)
        call(fallback)
    }

    private fun streamWithFailover(
        source: (LlmProvider) -> Flow<LlmStreamChunk>,
    ): Flow<LlmStreamChunk> = flow {
        if (fallbackUsed.get()) {
            emitAll(source(fallback))
            return@flow
        }

        var emittedOutput = false
        var transportError: LlmStreamChunk.Error? = null
        try {
            source(primary).collect { chunk ->
                when (chunk) {
                    is LlmStreamChunk.Delta, is LlmStreamChunk.ToolCallDelta -> {
                        emittedOutput = true
                        emit(chunk)
                    }
                    is LlmStreamChunk.Error -> {
                        if (!emittedOutput && chunk.cause?.isTransientTransportFailure() == true) {
                            transportError = chunk
                        } else {
                            emit(chunk)
                        }
                    }
                    LlmStreamChunk.Done -> if (transportError == null) emit(chunk)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!emittedOutput && failure.isTransientTransportFailure()) {
                transportError = LlmStreamChunk.Error(failure.message ?: "Cloud stream failed", failure)
            } else {
                throw failure
            }
        }

        val error = transportError ?: return@flow
        if (fallbackAvailable()) {
            Timber.tag("LlmFailover").w(error.cause, "Cloud stream failed; retrying with local model")
            fallbackUsed.set(true)
            emitAll(source(fallback))
        } else {
            emit(error)
        }
    }

    private suspend fun fallbackAvailable(): Boolean = try {
        fallback.isAvailable()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Timber.tag("LlmFailover").w(failure, "Local fallback availability check failed")
        false
    }
}