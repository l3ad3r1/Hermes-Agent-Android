package com.hermes.agent.data.hotfix

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashLoopPolicyTest {

    @get:Rule val tmp = TemporaryFolder()
    private val policy = CrashLoopPolicy(quickCrashWindowMs = 10_000, maxQuickCrashes = 3)

    @Test
    fun `three quick crashes in a row roll the patch back`() {
        var count = 0
        repeat(2) {
            val d = policy.onCrash(2_000, count, patchLoaded = true)
            assertEquals(false, d.rollBack)
            count = d.newCount
        }
        assertEquals(2, count)
        assertEquals(CrashLoopPolicy.Decision(0, true), policy.onCrash(2_000, count, patchLoaded = true))
    }

    @Test
    fun `late crashes and unpatched crashes do not count`() {
        assertEquals(CrashLoopPolicy.Decision(1, false), policy.onCrash(60_000, 1, patchLoaded = true))
        assertEquals(CrashLoopPolicy.Decision(0, false), policy.onCrash(1_000, 2, patchLoaded = false))
    }

    @Test
    fun `the counter file survives a round trip and reads 0 when absent or corrupt`() {
        val f = File(tmp.root, "hotfix/crash-count")
        val counter = CrashCounterFile(f)
        assertEquals(0, counter.read())
        counter.write(2)
        assertEquals(2, counter.read())
        f.writeText("garbage")
        assertEquals(0, counter.read())
    }

    @Test
    fun `hotfix state round-trips through the store`() {
        val store = HotfixStateStore(File(tmp.root, "hotfix"))
        assertEquals(HotfixState(), store.read())
        val staged = StagedPatch(2, "hermes-90-abc", "c".repeat(64), "d".repeat(32), 10, "n", "/x", true, 1L)
        store.update { it.copy(staged = staged, blockedPatches = listOf(BlockedPatch("hermes-90-abc", 1))) }
        store.update { it.copy(staged = it.staged!!.copy(installed = true)) }
        val read = store.read()
        assertEquals(true, read.staged!!.installed)
        assertEquals(listOf(BlockedPatch("hermes-90-abc", 1)), read.blockedPatches)
    }
}
