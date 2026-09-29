package com.hermes.agent.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtaChannelTest {

    @Test
    fun `test builds pick the highest version, pre-releases included, drafts skipped`() {
        val tags = listOf("v1.1.1", "v1.1.3", "v1.1.2", "v1.0.9")
        val drafts = listOf(false, true, false, false)
        assertEquals(2, newestIndex(tags, drafts))
    }

    @Test
    fun `an empty listing offers nothing`() {
        assertNull(newestIndex(emptyList(), emptyList()))
    }
}
