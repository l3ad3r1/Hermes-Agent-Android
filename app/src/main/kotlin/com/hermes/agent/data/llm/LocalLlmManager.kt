package com.hermes.agent.data.llm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceEngine.State
import com.arm.aichat.isModelLoaded
import com.hermes.agent.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class LocalLlmManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadCoordinator: LocalModelDownloadCoordinator,
    private val engine: InferenceEngine,
) {
    private val modelMutex = Mutex()

    private suspend fun activeModel(): DownloadableModel =
        ModelCatalog.byId(settingsRepository.current().selectedModelId)

    private suspend fun destinationDir(): File {
        val custom = settingsRepository.current().modelDownloadDir
        return if (custom.isNotBlank()) File(custom)
        else File(Environment.getExternalStorageDirectory(), ModelCatalog.DEFAULT_DIR_NAME)
    }

    private suspend fun currentModelFile(): File = File(destinationDir(), activeModel().fileName)

    /**
     * Whether the selected model is present. This does storage-provider (SAF
     * binder) and filesystem IO, so it MUST run off the main thread — every
     * caller reaches it from `viewModelScope` (Main), and a slow provider here
     * was blocking the main thread on model switch, ANR-ing the app (looked
     * like a crash) and stalling the UI.
     */
    suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.current()
        if (settings.localModelUri.isNotBlank()) {
            return@withContext runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(settings.localModelUri), "r")
                    ?.use { it.statSize != 0L } == true
            }.getOrDefault(false)
        }
        val model = activeModel()
        val file = currentModelFile()
        file.isFile && file.length() == model.sizeBytes
    }

    val isDownloading: StateFlow<Boolean> = downloadCoordinator.isDownloading
    val downloadProgress: StateFlow<Float> = downloadCoordinator.progress
    val downloadError: StateFlow<String> = downloadCoordinator.error

    suspend fun startDownload() {
        if (isDownloading.value || isModelDownloaded()) return
        if (!hasStorageAccess(context)) {
            downloadCoordinator.reportError(
                "Storage access is required to save the model. Grant it above and try again.",
            )
            return
        }
        downloadCoordinator.enqueue(activeModel(), destinationDir())
    }

    fun clearDownloadError() = downloadCoordinator.clearError()

    private suspend fun initializeLocked() {
        val settledState = engine.state.first {
            it !is State.Uninitialized && it !is State.Initializing
        }
        when {
            settledState.isModelLoaded -> return
            settledState is State.Error -> engine.cleanUp()
            settledState !is State.Initialized -> throw IllegalStateException(
                "Local model is busy (${settledState.javaClass.simpleName}). Try again.",
            )
        }
        if (!isModelDownloaded()) {
            throw IllegalStateException("Model not downloaded yet. Please download it in settings.")
        }
        val customUri = settingsRepository.current().localModelUri
        if (customUri.isNotBlank()) {
            val uri = Uri.parse(customUri)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                engine.loadModel("/proc/self/fd/${descriptor.fd}")
            } ?: throw IllegalStateException("Cannot open the custom model file. Choose it again.")
        } else {
            engine.loadModel(currentModelFile().absolutePath)
        }
    }

    fun generateResponse(systemPrompt: String, userPrompt: String): Flow<String> = flow {
        modelMutex.withLock {
            if (!engine.state.value.isModelLoaded) initializeLocked()
            // Always reset native chat state: the provider supplies a bounded transcript
            // on every call, including internal calls that have no explicit system message.
            engine.setSystemPrompt(systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT })
            engine.sendUserPrompt(userPrompt).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun setLocalModelUri(uri: String) = updateModelSelection("change the custom model") {
        settingsRepository.setLocalModelUri(uri)
    }

    suspend fun setSelectedModelId(id: String) = updateModelSelection("select that model") {
        settingsRepository.setSelectedModelId(id)
    }

    suspend fun setModelDownloadDir(dir: String) = updateModelSelection("change the model folder") {
        settingsRepository.setModelDownloadDir(dir)
    }

    private suspend fun updateModelSelection(
        action: String,
        persist: suspend () -> Unit,
    ) = modelMutex.withLock {
        try {
            // Native unload + persistence — keep it off the caller's thread
            // (SettingsViewModel starts these on viewModelScope = Main).
            withContext(Dispatchers.IO) {
                engine.cleanUp()
                persist()
            }
        } catch (error: Exception) {
            Timber.e(error, "Could not %s", action)
            downloadCoordinator.reportError(
                "Couldn't $action. Stop any active response and try again. " +
                    (error.message ?: "The local model could not be unloaded."),
            )
        }
    }

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT = "You are Jeeves, a helpful on-device assistant."

        fun hasStorageAccess(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
            }
    }
}
