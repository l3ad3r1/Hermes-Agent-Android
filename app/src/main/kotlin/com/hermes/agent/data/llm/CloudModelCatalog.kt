package com.hermes.agent.data.llm

import com.hermes.agent.data.remote.OpenAiApi
import com.hermes.agent.util.DispatcherProvider
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

interface CloudModelCatalog {
    suspend fun listModels(baseUrl: String, apiKey: String): List<String>
}

@Singleton
class OpenAiCloudModelCatalog @Inject constructor(
    private val api: OpenAiApi,
    private val dispatchers: DispatcherProvider,
) : CloudModelCatalog {

    override suspend fun listModels(baseUrl: String, apiKey: String): List<String> =
        withContext(dispatchers.io) {
            val cleanedBaseUrl = baseUrl.filter { it.code >= 0x20 }.trim().trimEnd('/')
            val parsed = cleanedBaseUrl.toHttpUrlOrNull()
            require(parsed != null && parsed.scheme in setOf("http", "https")) {
                "Enter a valid HTTP or HTTPS API URL."
            }
            val modelsUrl = if (parsed.encodedPath.trimEnd('/').endsWith("/models")) {
                parsed.toString()
            } else {
                "$cleanedBaseUrl/models"
            }
            val authorization = apiKey.filter { it.code >= 0x20 }.trim()
                .takeIf { it.isNotEmpty() }
                ?.let { "Bearer $it" }

            val response = retryTransientNetwork {
                api.models(modelsUrl, authorization)
            }
            response.data
                .asSequence()
                .map { it.id.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedBy { it.lowercase() }
                .toList()
        }

    private suspend fun <T> retryTransientNetwork(block: suspend () -> T): T {
        var lastFailure: IOException? = null
        repeat(NETWORK_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (failure: IOException) {
                lastFailure = failure
                Timber.tag("CloudModels").w(
                    failure,
                    "Model discovery transport failed (attempt %d/%d)",
                    attempt + 1,
                    NETWORK_ATTEMPTS,
                )
                if (attempt + 1 < NETWORK_ATTEMPTS) delay(NETWORK_RETRY_DELAY_MS)
            }
        }
        throw IOException(
            "Couldn't load models from this provider. Check the URL and connection, then retry.",
            lastFailure,
        )
    }

    private companion object {
        const val NETWORK_ATTEMPTS = 2
        const val NETWORK_RETRY_DELAY_MS = 350L
    }
}