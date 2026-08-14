package com.hermes.agent.data.tools

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhoneActionToolsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `alarm tool sends a native timer intent`() = runTest {
        val result = AlarmTool(context).execute(
            mapOf(
                "action" to JsonPrimitive("set_timer"),
                "duration_seconds" to JsonPrimitive(300),
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        val intent = nextStartedActivity()
        assertEquals(AlarmClock.ACTION_SET_TIMER, intent.action)
        assertEquals(300, intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
    }

    @Test
    fun `navigation tool encodes a destination in the maps URI`() = runTest {
        val result = NavigationTool(context).execute(
            mapOf(
                "action" to JsonPrimitive("navigate"),
                "query" to JsonPrimitive("Chandigarh Airport"),
                "mode" to JsonPrimitive("driving"),
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        val intent = nextStartedActivity()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("google.navigation", intent.data?.scheme)
        assertTrue(intent.dataString.orEmpty().contains("Chandigarh%20Airport"))
    }

    @Test
    fun `communication tool opens dialer rather than placing a call`() = runTest {
        val result = CommunicationTool(context).execute(
            mapOf(
                "action" to JsonPrimitive("dial"),
                "recipient" to JsonPrimitive("+911234567890"),
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        val intent = nextStartedActivity()
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel", intent.data?.scheme)
    }

    private fun nextStartedActivity(): Intent =
        Shadows.shadowOf(context.applicationContext as Application).nextStartedActivity
}
