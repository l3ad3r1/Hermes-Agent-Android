package com.hermes.agent.data.hotfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Fixtures in src/test/resources/hotfix are tiny stand-ins for a Tinker patch
 * (`assets/package_meta.txt` with TINKER_ID=hermes-90-0123456789ab, HERMES_PATCH_VERSION=1 and a
 * fake classes.dex): `unsigned.jar`; `signed-a.jar` (SHA256withRSA, SHA-256 digests, throwaway
 * test key A whose certificate SHA-256 is [KEY_A]); `signed-b.jar` (same content, throwaway key B);
 * `signed-a-and-b.jar` (signed-a.jar signed again by key B); `tampered-a.jar` (classes.dex changed
 * after signing); `extra-unsigned-entry-a.jar` (an entry added after signing);
 * `signed-a-version-2.jar` / `signed-a-no-version.jar` (key A, HERMES_PATCH_VERSION=2 / absent).
 * The keys were generated for these files, then discarded; they are unrelated to the release key.
 */
class PatchGateTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        val out = tmp.newFile(name)
        javaClass.getResourceAsStream("/hotfix/$name")!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun expected(file: File, base: String = BASE) =
        ExpectedPatch(base, 1, PatchDigest.sha256Hex(file), file.length())

    @Test
    fun `sha256 of a file matches the digest of its bytes`() {
        val f = tmp.newFile("x.bin").apply { writeText("hermes") }
        assertEquals(PatchDigest.sha256Hex("hermes".toByteArray()), PatchDigest.sha256Hex(f))
        assertEquals(64, PatchDigest.sha256Hex(f).length)
        assertTrue(PatchDigest.sameDigest(PatchDigest.sha256Hex(f), PatchDigest.sha256Hex(f).uppercase()))
    }

    @Test
    fun `a patch signed by the installed key for this build is accepted`() {
        val f = fixture("signed-a.jar")
        assertEquals(PatchGate.Verdict.Accepted, PatchGate.verify(f, expected(f), BASE, setOf(KEY_A)))
    }

    @Test
    fun `signature checks reject unsigned, foreign, tampered and partially signed archives`() {
        for (name in listOf("unsigned.jar", "signed-b.jar", "signed-a-and-b.jar", "tampered-a.jar", "extra-unsigned-entry-a.jar")) {
            val f = fixture(name)
            val sig = PatchSignatureVerifier.verify(f, setOf(KEY_A))
            assertTrue("$name must be rejected, got $sig", sig is PatchSignatureVerifier.Result.Rejected)
            assertTrue(name, PatchGate.verify(f, expected(f), BASE, setOf(KEY_A)) is PatchGate.Verdict.Rejected)
        }
    }

    @Test
    fun `an unknown installed signer rejects everything`() {
        val f = fixture("signed-a.jar")
        assertTrue(PatchSignatureVerifier.verify(f, emptySet()) is PatchSignatureVerifier.Result.Rejected)
    }

    @Test
    fun `digest or size that differs from the manifest is rejected`() {
        val f = fixture("signed-a.jar")
        val good = expected(f)
        assertTrue(PatchGate.verify(f, good.copy(sha256 = "0".repeat(64)), BASE, setOf(KEY_A)) is PatchGate.Verdict.Rejected)
        assertTrue(PatchGate.verify(f, good.copy(sizeBytes = f.length() + 1), BASE, setOf(KEY_A)) is PatchGate.Verdict.Rejected)
        assertTrue(PatchGate.verify(File(tmp.root, "missing.apk"), good, BASE, setOf(KEY_A)) is PatchGate.Verdict.Rejected)
    }

    @Test
    fun `a patch for another build is rejected, by manifest and by its own package meta`() {
        val f = fixture("signed-a.jar")
        // Manifest says another base.
        assertTrue(PatchGate.verify(f, expected(f, base = "hermes-91-0123456789ab"), "hermes-91-0123456789ab", setOf(KEY_A)) is PatchGate.Verdict.Rejected)
        // Manifest matches the installed id but the patch file itself was built for another base.
        assertTrue(PatchGate.verify(f, expected(f, base = "hermes-91-0123456789ab"), BASE, setOf(KEY_A)) is PatchGate.Verdict.Rejected)
        // Installed id unknown.
        assertTrue(PatchGate.verify(f, expected(f), null, setOf(KEY_A)) is PatchGate.Verdict.Rejected)
    }

    @Test
    fun `the manifest's patch version must equal the patch's own signed version`() {
        // Replay: an older signed patch republished under a higher manifest number.
        val v1 = fixture("signed-a.jar")
        assertTrue(PatchGate.verify(v1, expected(v1).copy(patchVersion = 2), BASE, setOf(KEY_A)) is PatchGate.Verdict.Rejected)
        val v2 = fixture("signed-a-version-2.jar")
        assertEquals(PatchGate.Verdict.Accepted, PatchGate.verify(v2, expected(v2).copy(patchVersion = 2), BASE, setOf(KEY_A)))
        assertTrue(PatchGate.verify(v2, expected(v2), BASE, setOf(KEY_A)) is PatchGate.Verdict.Rejected)
        val none = fixture("signed-a-no-version.jar")
        assertTrue(PatchGate.verify(none, expected(none), BASE, setOf(KEY_A)) is PatchGate.Verdict.Rejected)
    }

    @Test
    fun `package meta is read from the patch`() {
        val meta = PatchSignatureVerifier.packageMeta(fixture("signed-a.jar"))!!
        assertEquals(BASE, meta.getProperty("TINKER_ID"))
        assertEquals("1", meta.getProperty(PatchGate.PATCH_VERSION_KEY))
    }

    private companion object {
        const val BASE = "hermes-90-0123456789ab"
        const val KEY_A = "1c171c87e6eb7f3a5201c9437ebecb90c994ed97744add8bbc6bfd931b914e64"
    }
}
