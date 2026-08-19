package com.hermes.agent.data.settings
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.settings.CloudProviderProfile

import com.hermes.agent.domain.tool.ToolAuthorizationSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsToolAuthorizationSettings @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ToolAuthorizationSettings {
    override suspend fun autoApprovePhoneActions(): Boolean =
        settingsRepository.current().autoApprovePhoneActions

    override suspend fun trustedBackgroundPhoneActions(): Boolean =
        settingsRepository.current().trustedBackgroundPhoneActions
}
