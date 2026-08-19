package com.hermes.agent.data.security

import com.hermes.agent.domain.settings.CloudProviderProfile
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Secret handling at the settings boundary.
 *
 * The failure this guards against is not theoretical: a settings file restored
 * from another install carries ciphertext this device's keystore cannot read,
 * and the old code handed that ciphertext to providers as an API key. Every
 * provider answered "invalid key", which hid the real cause behind a wall of
 * 401s and made re-entering keys look like it had failed too.
 */
class EncryptedSettingsRepositoryTest {

    private lateinit var delegate: SettingsRepository
    private lateinit var keystore: KeystoreManager
    private lateinit var repo: EncryptedSettingsRepository

    /** Reversible stand-in for AES-GCM: byte-inverts, so "wrong key" is easy to fake. */
    private fun fakeCipher(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it] + 1).toByte() }
    private fun fakeDecipher(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it] - 1).toByte() }

    @Before
    fun setUp() {
        delegate = mockk(relaxed = true)
        keystore = mockk(relaxed = true)
        every { keystore.encrypt(any(), any()) } answers { fakeCipher(secondArg()) }
        every { keystore.decrypt(any(), any()) } answers { fakeDecipher(secondArg()) }
        repo = EncryptedSettingsRepository(delegate, keystore)
    }

    private fun profile(id: String, key: String) = CloudProviderProfile(
        id = id,
        name = id.uppercase(),
        baseUrl = "https://$id/v1",
        model = "model",
        apiKey = key,
        quality = 0.8,
        cost = 0.1,
        latency = 0.5,
        toolReliability = 0.9,
    )

    @Test
    fun `key round-trips through encrypt and decrypt`() = runTest {
        val stored = slot<String>()
        coEvery { delegate.setCloudApiKey(capture(stored)) } returns Unit

        repo.setCloudApiKey("sk-real-key-value")

        assertTrue("stored value must be marked ciphertext", stored.captured.startsWith("enc:v1:"))
        assertFalse("plaintext must not be persisted", stored.captured.contains("sk-real-key-value"))

        coEvery { delegate.current() } returns UserSettings(cloudApiKey = stored.captured)
        assertEquals("sk-real-key-value", repo.current().cloudApiKey)
    }

    @Test
    fun `undecryptable marked ciphertext reads as unset, never as the blob`() = runTest {
        every { keystore.decrypt(any(), any()) } throws SecurityException("key not found")
        val blob = "enc:v1:d2hhdGV2ZXI="
        coEvery { delegate.current() } returns UserSettings(cloudApiKey = blob)

        val key = repo.current().cloudApiKey

        // The whole point: an unreadable secret must not reach a provider.
        assertEquals("", key)
        assertFalse(key.contains("enc:v1:"))
    }

    @Test
    fun `unmarked plaintext survives, so legacy keys are not lost`() = runTest {
        every { keystore.decrypt(any(), any()) } throws SecurityException("not ciphertext")
        coEvery { delegate.current() } returns UserSettings(cloudApiKey = "legacy-plaintext-key")

        assertEquals("legacy-plaintext-key", repo.current().cloudApiKey)
    }

    @Test
    fun `encrypting an already-encrypted value is a no-op`() = runTest {
        val first = slot<String>()
        coEvery { delegate.setCloudApiKey(capture(first)) } returns Unit
        repo.setCloudApiKey("sk-real-key-value")
        val once = first.captured

        // Feeding a stored value back in is exactly what `updateProvider` does
        // for every profile the user did not edit.
        val second = slot<String>()
        coEvery { delegate.setCloudApiKey(capture(second)) } returns Unit
        repo.setCloudApiKey(once)

        assertEquals("re-encryption must not add a layer", once, second.captured)
    }

    @Test
    fun `sweep clears secrets this install cannot decrypt`() = runTest {
        every { keystore.decrypt(any(), any()) } throws SecurityException("key from another install")
        coEvery { delegate.current() } returns UserSettings(
            cloudApiKey = "enc:v1:d2hhdGV2ZXI=",
            githubPat = "enc:v1:d2hhdGV2ZXI=",
        )

        val cleared = repo.clearUnreadableSecrets()

        assertEquals(2, cleared)
        coVerify { delegate.setCloudApiKey("") }
        coVerify { delegate.setGithubPat("") }
    }

    @Test
    fun `sweep leaves readable secrets and legacy plaintext alone`() = runTest {
        val stored = slot<String>()
        coEvery { delegate.setCloudApiKey(capture(stored)) } returns Unit
        repo.setCloudApiKey("sk-real-key-value")

        coEvery { delegate.current() } returns UserSettings(
            cloudApiKey = stored.captured,      // marked, and decryptable here
            githubPat = "legacy-plaintext-pat", // unmarked: may still work
        )

        assertEquals(0, repo.clearUnreadableSecrets())
    }

    @Test
    fun `sweep blanks only the dead provider keys`() = runTest {
        val stored = slot<List<CloudProviderProfile>>()
        coEvery { delegate.setCloudProviderProfiles(capture(stored)) } returns Unit
        coEvery { delegate.current() } returns UserSettings(
            cloudProviderProfiles = listOf(
                profile("live", "plain-key"),                 // unmarked, keep
                profile("dead", "enc:v1:d2hhdGV2ZXI="),       // marked + undecryptable
            ),
        )
        every { keystore.decrypt(any(), any()) } throws SecurityException("no key")

        assertEquals(1, repo.clearUnreadableSecrets())
        assertEquals(listOf("plain-key", ""), stored.captured.map { it.apiKey })
    }

    @Test
    fun `saving all profiles does not bury the untouched ones`() = runTest {
        val stored = slot<List<CloudProviderProfile>>()
        coEvery { delegate.setCloudProviderProfiles(capture(stored)) } returns Unit

        repo.setCloudProviderProfiles(
            listOf(
                profile("a", "key-a"),
                profile("b", "key-b"),
            ),
        )
        val afterFirstSave = stored.captured

        // Editing one provider writes them all back; the untouched rows arrive
        // still encrypted and must come out byte-identical.
        repo.setCloudProviderProfiles(afterFirstSave)

        assertEquals(afterFirstSave.map { it.apiKey }, stored.captured.map { it.apiKey })

        coEvery { delegate.current() } returns UserSettings(cloudProviderProfiles = stored.captured)
        assertEquals(listOf("key-a", "key-b"), repo.current().cloudProviderProfiles.map { it.apiKey })
    }
}
