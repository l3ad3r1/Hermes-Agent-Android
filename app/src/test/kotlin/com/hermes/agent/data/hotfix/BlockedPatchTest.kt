package com.hermes.agent.data.hotfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The block list and the applied/staged versions belong to one base build, not to the app. */
class BlockedPatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base114 = "hermes-91-2ac2e2b80482"
    private val base115 = "hermes-92-abcdef012345"

    private fun applied(base: String, version: Int) = AppliedPatch(version, base, "d".repeat(32), "n", 1L)
    private fun staged(base: String, version: Int) =
        StagedPatch(version, base, "c".repeat(64), "d".repeat(32), 10, "n", "/x", true, 1L)

    @Test
    fun `a patch blocked on one base does not block the same number on the next base`() {
        val state = HotfixState().blocking(BlockedPatch(base114, 1))
        assertEquals(setOf(1), state.blockedFor(base114))
        assertEquals(emptySet<Int>(), state.blockedFor(base115))
    }

    @Test
    fun `the next release's first patch is offered after the previous release's first patch was removed`() {
        val state = HotfixState().blocking(BlockedPatch(base114, 1))
        val installed = InstalledBuild(
            versionName = "1.1.5",
            tinkerId = base115,
            blockedPatchVersions = state.blockedFor(base115),
        )
        val offer = PatchOffer(
            PatchManifest(baseTinkerId = base115, patchVersion = 1, sha256 = "a".repeat(64), size = 100),
            "https://example.test/p1.apk",
            "v1.1.5",
        )
        assertTrue(decideOta(installed, null, listOf(offer)) is OtaDecision.Patch)
    }

    @Test
    fun `a blocked patch stays blocked on its own base`() {
        val state = HotfixState().blocking(BlockedPatch(base114, 1), BlockedPatch(base114, 3))
        assertEquals(setOf(1, 3), state.blockedFor(base114))
    }

    @Test
    fun `an unknown installed id blocks and unblocks nothing`() {
        val state = HotfixState().blocking(BlockedPatch(base114, 1))
        assertEquals(emptySet<Int>(), state.blockedFor(null))
        assertEquals(emptySet<Int>(), state.blockedFor("not-an-id"))
    }

    @Test
    fun `blocking skips nulls, drops repeats and keeps the newest limit`() {
        val state = HotfixState().blocking(null, BlockedPatch(base114, 1), BlockedPatch(base114, 1))
        assertEquals(listOf(BlockedPatch(base114, 1)), state.blockedPatches)
        val many = (1..40).fold(HotfixState()) { s, v -> s.blocking(BlockedPatch(base114, v)) }
        assertEquals(32, many.blockedPatches.size)
        assertEquals(40, many.blockedPatches.last().patchVersion)
        assertEquals(9, many.blockedPatches.first().patchVersion)
    }

    @Test
    fun `an applied or staged patch from another base does not count for this one`() {
        val state = HotfixState(applied = applied(base114, 3), staged = staged(base114, 4))
        assertEquals(3, state.appliedVersionFor(base114))
        assertEquals(4, state.stagedVersionFor(base114))
        assertEquals(0, state.appliedVersionFor(base115))
        assertEquals(0, state.stagedVersionFor(base115))
        assertEquals(0, HotfixState().appliedVersionFor(base115))
    }

    @Test
    fun `a state file from before the change still reads and blocks nothing`() {
        val dir = File(tmp.root, "hotfix").apply { mkdirs() }
        File(dir, "state.json").writeText("""{"blockedPatchVersions":[1,2],"restartPending":true}""")
        val read = HotfixStateStore(dir).read()
        assertEquals(true, read.restartPending)
        assertEquals(emptyList<BlockedPatch>(), read.blockedPatches)
        assertEquals(emptySet<Int>(), read.blockedFor(base115))
    }

    @Test
    fun `the block list survives a write and a read`() {
        val store = HotfixStateStore(File(tmp.root, "hotfix"))
        store.update { it.blocking(BlockedPatch(base114, 2)) }
        assertEquals(setOf(2), store.read().blockedFor(base114))
        assertEquals(emptySet<Int>(), store.read().blockedFor(base115))
    }
}
