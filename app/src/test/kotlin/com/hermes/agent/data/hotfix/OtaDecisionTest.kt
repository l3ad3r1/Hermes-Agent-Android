package com.hermes.agent.data.hotfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaDecisionTest {

    private val base = "hermes-90-0123456789ab"
    private val installed = InstalledBuild(versionName = "1.1.3", tinkerId = base)
    private val full = FullUpdate("1.1.4", "https://github.com/x/y/releases/v1.1.4", "notes", "https://github.com/x/y/app.apk")

    private fun offer(version: Int, baseId: String = base, url: String = "https://example.test/p$version.apk") = PatchOffer(
        PatchManifest(baseTinkerId = baseId, patchVersion = version, sha256 = "b".repeat(64), size = 10),
        url,
        "v1.1.3",
    )

    @Test
    fun `nothing offered when there is neither a patch nor a newer release`() {
        assertEquals(OtaDecision.None, decideOta(installed, null, emptyList()))
    }

    @Test
    fun `a newer full release is offered when no patch matches`() {
        assertEquals(OtaDecision.FullApk(full), decideOta(installed, full, listOf(offer(1, baseId = "hermes-89-ffffffffffff"))))
    }

    @Test
    fun `a matching patch is preferred and the newest version wins`() {
        val d = decideOta(installed, full, listOf(offer(1), offer(3), offer(2)))
        assertTrue(d is OtaDecision.Patch)
        d as OtaDecision.Patch
        assertEquals(3, d.offer.manifest.patchVersion)
        assertEquals(full, d.fullUpdate)
    }

    @Test
    fun `patches for another base build are never offered`() {
        assertEquals(OtaDecision.None, decideOta(installed, null, listOf(offer(5, baseId = "hermes-90-0123456789ac"))))
        assertEquals(OtaDecision.None, decideOta(installed.copy(tinkerId = null), null, listOf(offer(5))))
    }

    @Test
    fun `applied, staged and blocked versions are not offered again`() {
        assertEquals(OtaDecision.None, decideOta(installed.copy(appliedPatchVersion = 2), null, listOf(offer(1), offer(2))))
        assertEquals(OtaDecision.None, decideOta(installed.copy(stagedPatchVersion = 2), null, listOf(offer(2))))
        assertEquals(OtaDecision.None, decideOta(installed.copy(blockedPatchVersions = setOf(3)), null, listOf(offer(3))))
        val d = decideOta(installed.copy(appliedPatchVersion = 2, blockedPatchVersions = setOf(4)), null, listOf(offer(3), offer(4)))
        assertEquals(3, (d as OtaDecision.Patch).offer.manifest.patchVersion)
    }

    @Test
    fun `no patch when patching is unavailable or the asset is missing`() {
        assertEquals(OtaDecision.FullApk(full), decideOta(installed.copy(patchingAvailable = false), full, listOf(offer(1))))
        assertEquals(OtaDecision.None, decideOta(installed, null, listOf(offer(1, url = ""))))
    }
}
