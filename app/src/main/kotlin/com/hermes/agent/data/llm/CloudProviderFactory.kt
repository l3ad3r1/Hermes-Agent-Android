package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.data.remote.OpenAiApi
import com.hermes.agent.domain.settings.CloudProviderProfile
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.util.DispatcherProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class CloudProviderFactory @Inject constructor(
    private val api: OpenAiApi,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val json: Json,
) : ProfileCloudProviderFactory {
    override fun create(profile: CloudProviderProfile): CloudLlmProvider =
        CloudLlmProvider(api, settings, dispatchers, json, profile)
}

interface ProfileCloudProviderFactory {
    fun create(profile: CloudProviderProfile): CloudLlmProvider
}
