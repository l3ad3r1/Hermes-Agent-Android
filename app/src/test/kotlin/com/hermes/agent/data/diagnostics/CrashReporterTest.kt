package com.hermes.agent.data.diagnostics

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashReporterTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `report holds version, device and the stack trace`() {
        val text = CrashReporter.format("1.0.9", "main", IllegalStateException("boom"), 0L)
        assertTrue(text.contains("Hermes: 1.0.9"))
        assertTrue(text.contains("Thread: main"))
        assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.contains("Android:"))
    }

    @Test
    fun `a written report waits until it is discarded`() {
        CrashReporter.discard(context)
        assertNull(CrashReporter.pending(context))
        CrashReporter.write(context, "report one")
        assertEquals("report one", CrashReporter.pending(context))
        CrashReporter.write(context, "report two")
        assertEquals("only the newest is kept", "report two", CrashReporter.pending(context))
        CrashReporter.discard(context)
        assertNull(CrashReporter.pending(context))
    }

    @Test
    fun `the installed handler saves the crash and still passes it on`() {
        CrashReporter.discard(context)
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var passedOn: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> passedOn = e }
        try {
            CrashReporter.install(context, "1.0.9")
            val error = RuntimeException("kaboom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertTrue(CrashReporter.pending(context)!!.contains("kaboom"))
            assertEquals(error, passedOn)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
            CrashReporter.discard(context)
        }
    }
}
