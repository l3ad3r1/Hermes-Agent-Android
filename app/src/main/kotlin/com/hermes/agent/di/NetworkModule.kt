package com.hermes.agent.di
import com.hermes.agent.domain.llm.*

import com.hermes.agent.data.remote.OpenAiApi
import com.hermes.agent.domain.settings.SettingsRepository
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Networking stack: OkHttp + Retrofit + kotlinx.serialization.
 *
 * The base URL is taken from user settings so the cloud LLM endpoint can
 * be retargeted (OpenAI → Azure OpenAI → vLLM → Ollama → self-hosted)
 * without a code change.
 *
 * The API instance is created lazily via a provider (not a Hilt-supplied
 * factory) so that base URL changes after the user edits Settings take
 * effect on the next API call. The CloudLlmProvider fetches the current
 * base URL from SettingsRepository on each call and rebuilds the API
 * instance — see [com.hermes.agent.data.llm.CloudLlmProvider] for the
 * usage pattern.
 *
 * For Phase 1 simplicity, a singleton [OpenAiApi] is provided that points
 * at the base URL configured at first injection. If the user changes the
 * base URL via Settings, they must restart the app for it to take effect.
 * Phase 2 will swap this for a per-call factory.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        pinningConfig: com.hermes.agent.data.security.CertificatePinningConfig,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .certificatePinner(pinningConfig.pinner)
            .connectTimeout(15, TimeUnit.SECONDS)
            // Reasoning models (o1/o3, DeepSeek R1, Nemotron reasoning, Claude
            // thinking) emit a multi-minute thinking phase before the first
            // token. 180s cut them off mid-think; 10 minutes is the deepest
            // floor in ReasoningStaleTimeout. RoutedProviderChain still applies
            // a tighter per-model withTimeout, and AgentLoopRunner caps the
            // whole turn, so a genuinely hung socket is still bounded.
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        json: Json,
    ): Retrofit {
        // The real per-request URL is supplied by CloudLlmProvider via @Url
        // (read live from settings), so this base URL is only a required
        // placeholder for Retrofit — it is overridden on every call.
        return Retrofit.Builder()
            .baseUrl("https://api.openai.com/v1/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenAiApi(retrofit: Retrofit): OpenAiApi =
        retrofit.create(OpenAiApi::class.java)
}
