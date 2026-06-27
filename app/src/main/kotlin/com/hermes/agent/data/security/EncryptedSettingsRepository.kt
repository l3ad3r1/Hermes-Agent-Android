package com.hermes.agent.data.security

import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.di.PlainSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps a DataStore-backed [SettingsRepository] so the cloud API key is
 * encrypted at rest via [KeystoreManager] before being persisted.
 *
 * Per Section 8 of the plan: "encryption of all stored data at rest."
 * Phase 1 stored the API key in plaintext inside DataStore. Phase 4
 * transparently encrypts / decrypts it on every [setCloudApiKey] /
 * [current] / [observe] call so the rest of the app doesn't need to
 * change.
 *
 * The encrypted blob is stored under the same `cloud_api_key` DataStore
 * key but is base64(iv || ciphertext). If it doesn't decode / decrypt
 * (e.g. a Phase 1 leftover plaintext value), the wrapper returns it
 * as-is so the user isn't locked out of their existing key.
 *
 * Constructor takes the underlying impl qualified with [PlainSettings]
 * so Hilt doesn't recurse: the [SettingsRepository] interface binding
 * in [com.hermes.agent.di.AppModule] points at this class, but this
 * class needs the DataStore-backed [SettingsRepositoryImpl] as its
 * delegate — qualified injection breaks the cycle.
 */
@Singleton
class EncryptedSettingsRepository @Inject constructor(
    @PlainSettings private val delegate: SettingsRepository,
    private val keystore: KeystoreManager,
) : SettingsRepository by delegate {

    override suspend fun current(): UserSettings {
        val plain = delegate.current()
        val encrypted = plain.cloudApiKey
        if (encrypted.isBlank()) return plain
        val decrypted = runCatching {
            val blob = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)
            String(keystore.decrypt(KeystoreManager.ALIAS_CLOUD_API_KEY, blob), Charsets.UTF_8)
        }.getOrElse { encrypted }
        return plain.copy(cloudApiKey = decrypted)
    }

    override fun observe(): Flow<UserSettings> =
        delegate.observe().map { plain ->
            val encrypted = plain.cloudApiKey
            if (encrypted.isBlank()) return@map plain
            val decrypted = runCatching {
                val blob = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)
                String(keystore.decrypt(KeystoreManager.ALIAS_CLOUD_API_KEY, blob), Charsets.UTF_8)
            }.getOrElse { encrypted }
            plain.copy(cloudApiKey = decrypted)
        }

    override suspend fun setCloudApiKey(key: String) {
        if (key.isBlank()) {
            delegate.setCloudApiKey("")
            return
        }
        val blob = keystore.encrypt(KeystoreManager.ALIAS_CLOUD_API_KEY, key.toByteArray(Charsets.UTF_8))
        val encoded = android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)
        delegate.setCloudApiKey(encoded)
    }

    /** Direct access to the encrypted blob (for export / backup flows). */
    suspend fun setCloudApiKeyEncrypted(base64Blob: String) {
        delegate.setCloudApiKey(base64Blob)
    }

    /** Direct access to the encrypted blob (for export / backup flows). */
    suspend fun getCloudApiKeyEncrypted(): String = delegate.current().cloudApiKey
}
