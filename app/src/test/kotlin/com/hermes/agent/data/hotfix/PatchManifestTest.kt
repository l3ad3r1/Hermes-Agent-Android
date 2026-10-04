package com.hermes.agent.data.hotfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchManifestTest {

    private val sha = "a".repeat(64)

    private fun json(
        base: String = "hermes-90-0123456789ab",
        version: Int = 2,
        sha256: String = sha,
        size: Long = 1234,
        extra: String = "",
    ) = """{"schema":1,"baseTinkerId":"$base","patchVersion":$version,"sha256":"$sha256","size":$size,
        |"notes":"Fixes the calendar crash","minRestartPrompt":false$extra}""".trimMargin()

    @Test
    fun `a valid manifest parses with defaults`() {
        val m = PatchManifest.parse(json()).getOrThrow()
        assertEquals("hermes-90-0123456789ab", m.baseTinkerId)
        assertEquals(2, m.patchVersion)
        assertEquals(1234L, m.size)
        assertFalse(m.minRestartPrompt)
        assertEquals("hermes-patch-hermes-90-0123456789ab-2.apk", m.assetName)
    }

    @Test
    fun `unknown fields are ignored and an explicit asset name is kept`() {
        val m = PatchManifest.parse(json(extra = ""","asset":"fix.apk","futureField":42""")).getOrThrow()
        assertEquals("fix.apk", m.assetName)
    }

    @Test
    fun `malformed or out-of-range manifests are rejected whole`() {
        val bad = listOf(
            json(sha256 = "A".repeat(64)),               // uppercase hex
            json(sha256 = "abc"),                        // short digest
            json(version = 0),
            json(size = 0),
            json(size = PatchManifest.MAX_PATCH_BYTES + 1),
            json(base = "90-numeric-start"),
            json(base = "hermes 90"),
            json(extra = ""","asset":"../../evil.apk""""),
            json(extra = ""","asset":"fix.dex""""),
            json().replace("\"schema\":1", "\"schema\":2"),
            json(extra = ""","notes":"${"x".repeat(PatchManifest.MAX_NOTES + 1)}""""),
            "not json",
            "{}",
        )
        bad.forEach { assertTrue("should reject: ${it.take(120)}", PatchManifest.parse(it).isFailure) }
    }

    @Test
    fun `manifest asset names are recognised`() {
        assertTrue(PatchManifest.isManifestAsset("hermes-patch.json"))
        assertTrue(PatchManifest.isManifestAsset("hermes-patch-hermes-90-abc.json"))
        assertFalse(PatchManifest.isManifestAsset("hermes-1.1.3.apk"))
        assertFalse(PatchManifest.isManifestAsset("notes.json"))
    }

    @Test
    fun `TINKER_ID matching is exact and never matches a missing id`() {
        assertTrue(TinkerIds.matches("hermes-90-abc", "hermes-90-abc"))
        assertFalse(TinkerIds.matches("hermes-90-abc", "hermes-90-abd"))
        assertFalse(TinkerIds.matches("hermes-90-abc", "HERMES-90-ABC"))
        assertFalse(TinkerIds.matches(null, "hermes-90-abc"))
        assertFalse(TinkerIds.matches("", ""))
        assertFalse(TinkerIds.matches("hermes-90-abc", null))
    }
}
