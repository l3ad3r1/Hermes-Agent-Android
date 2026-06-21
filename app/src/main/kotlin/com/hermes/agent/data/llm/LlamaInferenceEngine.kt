package com.hermes.agent.data.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JNI wrapper for libhermes-llama.so (built from app/src/main/cpp/ai_chat.cpp).
 *
 * The native library is only present when the project is built with NDK + a local
 * llama.cpp checkout (see hermes.local.properties and app/build.gradle.kts).
 * When the .so is absent [isNativeAvailable] is false and all inference methods
 * throw [IllegalStateException] — callers must check [isNativeAvailable] first.
 *
 * All JNI calls are dispatched on a single-threaded IO dispatcher because
 * llama.cpp's C++ globals are not thread-safe.
 */
@Singleton
class LlamaInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    @Volatile private var engineInitialized = false
    @Volatile private var modelLoaded = false
    @Volatile private var loadedModelPath: String? = null

    companion object {
        val isNativeAvailable: Boolean = runCatching {
            System.loadLibrary("hermes-llama")
            true
        }.getOrElse {
            Timber.w("hermes-llama native library not available: ${it.message}")
            false
        }
    }

    // ── JNI declarations (names must match ai_chat.cpp JNIEXPORT functions) ──

    private external fun init(nativeLibDir: String)
    private external fun loadModel(modelPath: String): Int
    private external fun prepare(): Int
    private external fun systemInfo(): String
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun unloadModel()
    private external fun shutdown()

    // ── Public API ────────────────────────────────────────────────────────────

    /** Initialize the GGML backends. Must be called before [ensureModel]. */
    suspend fun initialize() = withContext(llamaDispatcher) {
        check(isNativeAvailable) { "Native library not available" }
        if (engineInitialized) return@withContext
        init(nativeLibDir)
        engineInitialized = true
        Timber.i("LlamaInferenceEngine initialized. System info: ${systemInfo()}")
    }

    /**
     * Load the GGUF model at [modelPath] if not already loaded.
     * Unloads any previously loaded model first.
     */
    suspend fun ensureModel(modelPath: String) = withContext(llamaDispatcher) {
        check(engineInitialized) { "Call initialize() first" }
        if (loadedModelPath == modelPath && modelLoaded) return@withContext

        if (modelLoaded) {
            Timber.d("Unloading previous model")
            unloadModel()
            modelLoaded = false
            loadedModelPath = null
        }

        val f = File(modelPath)
        require(f.exists() && f.isFile && f.canRead()) {
            "Model file not readable: $modelPath"
        }

        Timber.i("Loading model: $modelPath")
        val loadResult = loadModel(modelPath)
        if (loadResult != 0) error("loadModel() failed with code $loadResult")

        val prepResult = prepare()
        if (prepResult != 0) error("prepare() failed with code $prepResult")

        modelLoaded = true
        loadedModelPath = modelPath
        Timber.i("Model loaded: $modelPath")
    }

    /**
     * Streams tokens for a conversation.
     *
     * [systemPrompt] is encoded once (resets KV cache). [conversationText] contains
     * the formatted conversation history (all turns except system) ending with the
     * user's latest message. Tokens are emitted as they are generated; the flow
     * completes when EOG or [maxTokens] is reached.
     */
    fun generateStream(
        systemPrompt: String,
        conversationText: String,
        maxTokens: Int = 1024,
    ): Flow<String> = flow {
        check(engineInitialized && modelLoaded) { "Engine not ready" }

        val sysResult = processSystemPrompt(
            systemPrompt.ifBlank { "You are Hermes, a helpful AI assistant." }
        )
        if (sysResult != 0) error("processSystemPrompt() failed ($sysResult)")

        val userResult = processUserPrompt(conversationText, maxTokens)
        if (userResult != 0) error("processUserPrompt() failed ($userResult)")

        while (true) {
            val token = generateNextToken() ?: break
            if (token.isNotEmpty()) emit(token)
        }
    }.flowOn(llamaDispatcher)

    /** Release model and all llama.cpp resources. */
    suspend fun destroy() = withContext(llamaDispatcher) {
        if (modelLoaded) {
            unloadModel()
            modelLoaded = false
            loadedModelPath = null
        }
        if (engineInitialized) {
            shutdown()
            engineInitialized = false
        }
    }
}
