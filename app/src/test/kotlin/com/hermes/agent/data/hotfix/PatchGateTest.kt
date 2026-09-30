package com.hermes.agent.data.hotfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Fixtures in src/test/resources/hotfix are tiny stand-ins for a Tinker patch
 * (`assets/package_meta.txt` with TINKER_ID=hermes-90-0123456789ab and a fake classes.dex):
 * `unsigned.jar`; `signed-a.jar` (SHA256withRSA, SHA-256 digests, throwaway test key A whose
 * certificate SHA-256 is [KEY_A]); `signed-b.jar` (same content, throwaway key B);
 * `tampered-a.jar` (classes.dex changed after signing); `extra-unsigned-entry-a.jar` (an entry
 * added after signing). The keys exist only for these files and are unrelated to the release key.
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
        for (name in listOf("unsigned.jar", "signed-b.jar", "tampered-a.jar", "extra-unsigned-entry-a.jar")) {
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
    fun `package meta is read from the patch`() {
        val meta = PatchSignatureVerifier.packageMeta(fixture("signed-a.jar"))!!
        assertEquals(BASE, meta.getProperty("TINKER_ID"))
    }

    private companion object {
        const val BASE = "hermes-90-0123456789ab"
        const val KEY_A = "748bb9f90bfeb186d66123874ece7df8d6019e98c0c0bb6fef87f4930798ac50"
    }
}
