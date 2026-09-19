package com.hermes.agent.data.export

import com.hermes.agent.data.local.LocalBot
import com.hermes.agent.data.local.LocalBotStore
import com.hermes.agent.data.remote.BotProfileStore
import com.hermes.agent.data.remote.ChiefOfBots
import com.hermes.agent.domain.settings.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Bots hub's setup, for the backup file's `extras`.
 *
 * It lives in preferences the shared backup library cannot see, so the app reads and writes it
 * here. The bots' chats are not part of this: they are ordinary conversations, and travel with
 * the "Chat history" section under the same ids.
 *
 * Deliberately not carried: the embedded Tailscale node's key (it identifies this device on the
 * tailnet, and a restored copy would make two devices claim one node) and the gateway key (a
 * secret, so it goes with the encrypted API keys instead).
 */
@Singleton
class BotsBackup @Inject constructor(
    private val localBots: LocalBotStore,
    private val profiles: BotProfileStore,
    private val settings: SettingsRepository,
) {

    @Serializable
    data class Snapshot(
        val localBots: List<LocalBot> = emptyList(),
        val chiefName: String = ChiefOfBots.DEFAULT_NAME,
        /** PC profiles in the tab bar, without the always-present `default`. */
        val desktopBots: List<String> = emptyList(),
        val gatewayUrl: String = "",
        val gatewayEnabled: Boolean = false,
    )

    suspend fun export(): JsonElement {
        val current = settings.current()
        val snapshot = Snapshot(
            localBots = localBots.bots.value,
            chiefName = localBots.chiefName.value,
            desktopBots = profiles.profiles.value.filter { it != BotProfileStore.DEFAULT },
            gatewayUrl = current.remoteGatewayUrl,
            gatewayEnabled = current.remoteGatewayEnabled,
        )
        return json.encodeToJsonElement(Snapshot.serializer(), snapshot)
    }

    /**
     * Applies [element], keeping what is already here unless [overwrite]. A name or a URL that has
     * been set on this install is the user's more recent choice, so it only gives way to the file
     * when asked.
     */
    suspend fun restore(element: JsonElement, overwrite: Boolean): ImportReport {
        val snapshot = json.decodeFromJsonElement(Snapshot.serializer(), element)
        var report = localBots.restore(snapshot.localBots, overwrite)

        if (snapshot.chiefName != ChiefOfBots.DEFAULT_NAME &&
            (overwrite || localBots.chiefName.value == ChiefOfBots.DEFAULT_NAME)
        ) {
            localBots.setChiefName(snapshot.chiefName)
            report += ImportReport(added = 1)
        }

        for (name in snapshot.desktopBots) {
            report += if (profiles.add(name)) ImportReport(added = 1) else ImportReport(skipped = 1)
        }

        val current = settings.current()
        if (snapshot.gatewayUrl.isNotBlank() && (overwrite || current.remoteGatewayUrl.isBlank())) {
            settings.setRemoteGatewayUrl(snapshot.gatewayUrl)
            settings.setRemoteGatewayEnabled(snapshot.gatewayEnabled)
            report += ImportReport(added = 1)
        }
        return report
    }

    companion object {
        /** The key this section is stored under in the backup file's `extras`. */
        const val KEY = "bots"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
