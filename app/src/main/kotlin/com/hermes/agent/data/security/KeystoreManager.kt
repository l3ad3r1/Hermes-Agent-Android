package com.hermes.agent.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the Android Keystore for encrypting secrets at rest.
 *
 * Used in Phase 1 to encrypt the cloud API key when stored in DataStore
 * (currently stored as plaintext for simplicity — Phase 4 will swap in
 * this keystore-backed encryption). The wrapper itself is wired and tested
 * now so the migration path is mechanical.
 *
 * The hardware-backed keystore on the S24 Ultra (StrongBox / TEE) is the
 * privacy foundation described in Section 4.2 and Section 8 of the plan.
 */
@Singleton
class KeystoreManager @Inject constructor() {

    private val keystore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun ensureKey(alias: String): SecretKey {
        keystore.getKey(alias, null)?.let { return it as SecretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Encrypts the given plaintext; returns IV || ciphertext. */
    fun encrypt(alias: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, ensureKey(alias))
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        return iv + ct
    }

    /** Decrypts a blob produced by [encrypt]; returns the plaintext. */
    fun decrypt(alias: String, blob: ByteArray): ByteArray {
        require(blob.size > IV_SIZE) { "ciphertext blob too short" }
        val iv = blob.copyOfRange(0, IV_SIZE)
        val ct = blob.copyOfRange(IV_SIZE, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, ensureKey(alias), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    fun deleteKey(alias: String) {
        runCatching { keystore.deleteEntry(alias) }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_SIZE = 12 // GCM standard IV length

        const val ALIAS_CLOUD_API_KEY = "hermes.cloud_api_key"
        const val ALIAS_DB_AT_REST = "hermes.db_at_rest"
    }
}
