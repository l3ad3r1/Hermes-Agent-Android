package com.hermes.agent.data.security

import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.di.PlainSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
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

    private companion object {
        /**
         * Marks a value this class encrypted. Its presence means "this is
         * ciphertext" without having to guess from the shape of the string,
         * which is what lets a failed decrypt be reported rather than leaked,
         * and makes re-encryption a no-op.
         */
        const val ENCRYPTED_PREFIX = "enc:v1:"
    }

    private fun decryptSecret(encrypted: String, alias: String): String {
        if (encrypted.isBlank()) return encrypted

        if (encrypted.startsWith(ENCRYPTED_PREFIX)) {
            val payload = encrypted.removePrefix(ENCRYPTED_PREFIX)
            return runCatching {
                val blob = java.util.Base64.getDecoder().decode(payload)
                String(keystore.decrypt(alias, blob), Charsets.UTF_8)
            }.getOrElse {
                // Definitely ciphertext, and unreadable: the keystore key is
                // gone (settings restored from another install, keystore
                // cleared). Returning the blob would hand it to a provider as
                // an API key, which comes back as "invalid key" and buries the
                // real cause. Report it as unset instead.
                Timber.tag("Settings").w(
                    "could not decrypt secret for alias %s — treating as unset", alias,
                )
                ""
            }
        }

        // No marker: either a blob written before the prefix existed, or a
        // Phase 1 plaintext leftover. Try to decrypt; if that fails the value
        // really is plaintext, so hand it back rather than locking the user out.
        return runCatching {
            val blob = java.util.Base64.getDecoder().decode(encrypted)
            String(keystore.decrypt(alias, blob), Charsets.UTF_8)
        }.getOrElse { encrypted }
    }

    private fun encryptSecret(plain: String, alias: String): String {
        if (plain.isBlank()) return ""
        // Idempotent. Callers such as `updateProvider` read every profile and
        // write them all back, so untouched values make a decrypt/encrypt round
        // trip on every save. Without this guard a value that arrives still
        // encrypted gains another layer each time, burying the original deeper
        // with every edit.
        if (plain.startsWith(ENCRYPTED_PREFIX)) return plain
        val blob = keystore.encrypt(alias, plain.toByteArray(Charsets.UTF_8))
        return ENCRYPTED_PREFIX + java.util.Base64.getEncoder().encodeToString(blob)
    }

    /**
     * Blank every secret this install can no longer read, returning how many
     * were cleared.
     *
     * Restoring a backup copies ciphertext encrypted under the *source*
     * install's keystore key. This device cannot decrypt it and never will, so
     * the value is not a secret any more — it is inert bytes wearing a secret's
     * name. Leaving it in place is actively harmful: the settings screen shows a
     * populated key field, so the user believes a key is configured, and
     * `SettingsViewModel.updateProvider` rewrites every provider profile on each
     * edit, carrying the dead value along.
     *
     * Only values [ENCRYPTED_PREFIX] marks are touched. An unmarked value may be
     * a legacy plaintext key that still works, and must survive.
     */
    suspend fun clearUnreadableSecrets(): Int {
        val raw = delegate.current()
        var cleared = 0

        suspend fun sweep(value: String, alias: String, clear: suspend () -> Unit) {
            if (isUnreadable(value, alias)) {
                clear()
                cleared++
            }
        }

        // Written through the delegate, not this class's setters: the values
        // that stay are already ciphertext and must not be encrypted again.
        sweep(raw.cloudApiKey, KeystoreManager.ALIAS_CLOUD_API_KEY) { delegate.setCloudApiKey("") }
        sweep(raw.auxApiKey, KeystoreManager.ALIAS_AUX_API_KEY) { delegate.setAuxApiKey("") }
        sweep(raw.githubPat, KeystoreManager.ALIAS_GITHUB_PAT) { delegate.setGithubPat("") }
        sweep(raw.apiServerKey, KeystoreManager.ALIAS_API_SERVER_KEY) { delegate.setApiServerKey("") }
        sweep(raw.sshPassword, KeystoreManager.ALIAS_SSH_PASSWORD) { delegate.setSshPassword("") }

        val profiles = raw.cloudProviderProfiles
        val dead = profiles.count { isUnreadable(it.apiKey, KeystoreManager.ALIAS_PROVIDER_API_KEYS) }
        if (dead > 0) {
            delegate.setCloudProviderProfiles(
                profiles.map { profile ->
                    if (isUnreadable(profile.apiKey, KeystoreManager.ALIAS_PROVIDER_API_KEYS)) {
                        profile.copy(apiKey = "")
                    } else {
                        profile
                    }
                },
            )
            cleared += dead
        }

        if (cleared > 0) {
            Timber.tag("Settings").w(
                "cleared %d secret(s) this install cannot decrypt — re-enter them in Settings",
                cleared,
            )
        }
        return cleared
    }

    /** True only for a value we marked as ciphertext and then failed to decrypt. */
    private fun isUnreadable(value: String, alias: String): Boolean {
        if (!value.startsWith(ENCRYPTED_PREFIX)) return false
        return runCatching {
            val blob = java.util.Base64.getDecoder().decode(value.removePrefix(ENCRYPTED_PREFIX))
            keystore.decrypt(alias, blob)
        }.isFailure
    }

    override suspend fun current(): UserSettings {
        val plain = delegate.current()
        return plain.copy(
            cloudApiKey = decryptSecret(plain.cloudApiKey, KeystoreManager.ALIAS_CLOUD_API_KEY),
            auxApiKey = decryptSecret(plain.auxApiKey, KeystoreManager.ALIAS_AUX_API_KEY),
            cloudProviderProfiles = decryptProviderProfiles(plain.cloudProviderProfiles),
            githubPat = decryptSecret(plain.githubPat, KeystoreManager.ALIAS_GITHUB_PAT),
            apiServerKey = decryptSecret(plain.apiServerKey, KeystoreManager.ALIAS_API_SERVER_KEY),
            sshPassword = decryptSecret(plain.sshPassword, KeystoreManager.ALIAS_SSH_PASSWORD),
            backupPassphrase = decryptSecret(
                plain.backupPassphrase, KeystoreManager.ALIAS_BACKUP_PASSPHRASE,
            ),
        )
    }

    override fun observe(): Flow<UserSettings> =
        delegate.observe().map { plain ->
            plain.copy(
                cloudApiKey = decryptSecret(plain.cloudApiKey, KeystoreManager.ALIAS_CLOUD_API_KEY),
                auxApiKey = decryptSecret(plain.auxApiKey, KeystoreManager.ALIAS_AUX_API_KEY),
                cloudProviderProfiles = decryptProviderProfiles(plain.cloudProviderProfiles),
                githubPat = decryptSecret(plain.githubPat, KeystoreManager.ALIAS_GITHUB_PAT),
                apiServerKey = decryptSecret(plain.apiServerKey, KeystoreManager.ALIAS_API_SERVER_KEY),
                sshPassword = decryptSecret(plain.sshPassword, KeystoreManager.ALIAS_SSH_PASSWORD),
                backupPassphrase = decryptSecret(
                    plain.backupPassphrase, KeystoreManager.ALIAS_BACKUP_PASSPHRASE,
                ),
            )
        }

    override suspend fun setCloudApiKey(key: String) {
        delegate.setCloudApiKey(encryptSecret(key, KeystoreManager.ALIAS_CLOUD_API_KEY))
    }

    override suspend fun setAuxApiKey(key: String) {
        delegate.setAuxApiKey(encryptSecret(key, KeystoreManager.ALIAS_AUX_API_KEY))
    }

    override suspend fun setCloudProviderProfiles(
        profiles: List<com.hermes.agent.domain.settings.CloudProviderProfile>,
    ) {
        delegate.setCloudProviderProfiles(
            profiles.map { profile ->
                profile.copy(
                    apiKey = encryptSecret(profile.apiKey, KeystoreManager.ALIAS_PROVIDER_API_KEYS),
                )
            },
        )
    }

    private fun decryptProviderProfiles(
        profiles: List<com.hermes.agent.domain.settings.CloudProviderProfile>,
    ): List<com.hermes.agent.domain.settings.CloudProviderProfile> = profiles.map { profile ->
        profile.copy(
            apiKey = decryptSecret(profile.apiKey, KeystoreManager.ALIAS_PROVIDER_API_KEYS),
        )
    }

    override suspend fun setGithubPat(pat: String) {
        delegate.setGithubPat(encryptSecret(pat, KeystoreManager.ALIAS_GITHUB_PAT))
    }

    override suspend fun setApiServerKey(key: String) {
        delegate.setApiServerKey(encryptSecret(key, KeystoreManager.ALIAS_API_SERVER_KEY))
    }

    override suspend fun setSshPassword(password: String) {
        delegate.setSshPassword(encryptSecret(password, KeystoreManager.ALIAS_SSH_PASSWORD))
    }

    override suspend fun setBackupPassphrase(passphrase: String) {
        delegate.setBackupPassphrase(
            encryptSecret(passphrase, KeystoreManager.ALIAS_BACKUP_PASSPHRASE),
        )
    }

    /** Direct access to the encrypted blob (for export / backup flows). */
    suspend fun setCloudApiKeyEncrypted(base64Blob: String) {
        delegate.setCloudApiKey(base64Blob)
    }

    /** Direct access to the encrypted blob (for export / backup flows). */
    suspend fun getCloudApiKeyEncrypted(): String = delegate.current().cloudApiKey
}
