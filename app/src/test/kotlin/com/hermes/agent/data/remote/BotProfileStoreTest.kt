package com.hermes.agent.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BotProfileStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store() = BotProfileStore(context)

    @Test
    fun `starts with the default profile`() {
        assertEquals(listOf("default"), store().profiles.value)
    }

    @Test
    fun `added profiles survive a restart and keep default first`() {
        val first = store()
        assertTrue(first.add("redditbot"))
        assertTrue(first.add("coder"))

        assertEquals(listOf("default", "redditbot", "coder"), BotProfileStore(context).profiles.value)
    }

    @Test
    fun `refuses duplicates and names that would escape the URL path`() {
        val store = store()
        assertTrue(store.add("redditbot"))

        assertFalse(store.add("redditbot"))
        assertFalse(store.add("default"))
        assertFalse(store.add("../sessions"))
        assertFalse(store.add(" "))
        assertEquals(listOf("default", "redditbot"), store.profiles.value)
    }

    @Test
    fun `removes a profile but never the default`() {
        val store = store()
        store.add("redditbot")

        store.remove("redditbot")
        store.remove("default")

        assertEquals(listOf("default"), store.profiles.value)
        assertEquals(listOf("default"), BotProfileStore(context).profiles.value)
    }
}
