package com.hermes.agent.data.hotfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PatchAdmissionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val md5 = "a".repeat(32)
    private val sha = "b".repeat(64)

    private fun staged(path: String, installed: Boolean = false) = StagedPatch(
        patchVersion = 3, baseTinkerId = BASE, sha256 = sha, md5 = md5, sizeBytes = 10, notes = "",
        path = path, stagedAtMillis = 0, installed = installed,
    )

    private fun applied(sha256: String = sha) =
        AppliedPatch(2, BASE, md5, "", 0, sha256 = sha256, sizeBytes = 10)

    @Test
    fun `the staged download is admitted with the staged record`() {
        val inbox = tmp.newFolder("inbox")
        val f = File(inbox, "p.apk").apply { writeText("x") }
        val e = PatchAdmission.expectedFor(HotfixState(staged = staged(f.path)), f, md5.uppercase(), emptyList())
        assertEquals(ExpectedPatch(BASE, 3, sha, 10), e)
    }

    @Test
    fun `a file elsewhere, or with another md5, is not admitted`() {
        val inbox = tmp.newFolder("inbox")
        val staged = File(inbox, "p.apk").apply { writeText("x") }
        val other = tmp.newFile("sdcard-patch.apk")
        val state = HotfixState(staged = staged(staged.path))
        assertNull(PatchAdmission.expectedFor(state, other, md5, emptyList()))
        assertNull(PatchAdmission.expectedFor(state, staged, "c".repeat(32), emptyList()))
        assertNull(PatchAdmission.expectedFor(state, staged, null, emptyList()))
        assertNull(PatchAdmission.expectedFor(HotfixState(), staged, md5, emptyList()))
        // Path traversal back out of Tinker's directory does not count as inside it.
        val tinker = tmp.newFolder("tinker")
        assertNull(PatchAdmission.expectedFor(state, File(tinker, "../sdcard-patch.apk"), md5, listOf(tinker)))
    }

    @Test
    fun `Tinker's own copy of the applied patch is admitted for oat repair`() {
        val tinker = tmp.newFolder("tinker")
        val copy = File(tinker, "patch-aaaa/patch-aaaa.apk").apply { parentFile!!.mkdirs(); writeText("x") }
        val e = PatchAdmission.expectedFor(HotfixState(applied = applied()), copy, md5, listOf(tinker))
        assertEquals(ExpectedPatch(BASE, 2, sha, 10), e)
        // Not outside Tinker's directory, and not for an applied record without a pinned digest.
        val outside = tmp.newFile("elsewhere.apk")
        assertNull(PatchAdmission.expectedFor(HotfixState(applied = applied()), outside, md5, listOf(tinker)))
        assertNull(PatchAdmission.expectedFor(HotfixState(applied = applied(sha256 = "")), copy, md5, listOf(tinker)))
    }

    @Test
    fun `an interrupted preparation is dropped, a prepared one is kept`() {
        val waiting = HotfixState(staged = staged("/x"))
        val after = abandonInterruptedPreparation(waiting, 5)
        assertNull(after.staged)
        assertEquals(HotfixEvent.Kind.INSTALL_FAILED, after.lastEvent?.kind)
        val prepared = HotfixState(staged = staged("/x", installed = true))
        assertEquals(prepared, abandonInterruptedPreparation(prepared, 5))
        assertTrue(abandonInterruptedPreparation(HotfixState(), 5) == HotfixState())
    }

    private companion object {
        const val BASE = "hermes-90-0123456789ab"
    }
}
