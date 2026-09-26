package com.hermes.agent.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeedPaletteTest {

    @Test
    fun `every preset round-trips through its storage key`() {
        SeedPreset.entries.forEach { assertEquals(it, SeedPreset.fromStorageKey(it.storageKey)) }
    }

    @Test
    fun `empty or unknown keys mean no preset`() {
        assertNull(SeedPreset.fromStorageKey(""))
        assertNull(SeedPreset.fromStorageKey(null))
        assertNull(SeedPreset.fromStorageKey("neon"))
    }

    @Test
    fun `storage keys are unique`() {
        assertEquals(SeedPreset.entries.size, SeedPreset.entries.map { it.storageKey }.toSet().size)
    }

    @Test
    fun `light and dark schemes differ and each preset gives its own colours`() {
        val forestDark = seedColorScheme(SeedPreset.FOREST, dark = true)
        assertNotEquals(forestDark.background, seedColorScheme(SeedPreset.FOREST, dark = false).background)
        assertNotEquals(forestDark.primary, seedColorScheme(SeedPreset.ROSE, dark = true).primary)
    }
}
