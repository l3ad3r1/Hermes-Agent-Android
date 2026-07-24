package com.hermes.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sassybutler.alarm.ButlerSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the one path unit tests cannot: actually loading Butler's ~92 MB ONNX model out of
 * the merged APK's assets and playing synthesized speech through AudioTrack.
 *
 * This is the cross-module payoff — `:app` driving `:feature:butler`'s on-device voice — and it
 * only proves anything on a real device/emulator, because the model lives in `assets/tts/`.
 */
@RunWith(AndroidJUnit4::class)
class ButlerSpeechInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun warmUp_loadsTheOnnxModelFromMergedAssets() = runBlocking {
        val speech = ButlerSpeech(context)
        assertFalse("engine must not be loaded before first use", speech.isLoaded)

        val ok = withContext(Dispatchers.Default) { speech.warmUp() }

        assertTrue("ONNX model failed to load from assets/tts/", ok)
        assertTrue("isLoaded should latch after warmUp", speech.isLoaded)
        speech.release()
    }

    @Test
    fun speak_synthesizesAndPlaysToCompletion() = runBlocking {
        val speech = ButlerSpeech(context)

        // speak() suspends until AudioTrack has drained; UNAVAILABLE means the caller
        // should fall back to the platform engine.
        val result = speech.speak("Good morning. Your merged app appears to be working.")

        assertEquals(
            "ButlerSpeech.speak did not play — no audio was produced",
            ButlerSpeech.SpeakResult.SPOKEN,
            result,
        )
        assertTrue(speech.isLoaded)
        speech.release()
    }

    @Test
    fun speak_returnsUnavailableForBlankTextWithoutLoadingTheModel() = runBlocking {
        val speech = ButlerSpeech(context)

        assertEquals(ButlerSpeech.SpeakResult.UNAVAILABLE, speech.speak("   "))
        assertFalse("blank text must not trigger a 92 MB model load", speech.isLoaded)
    }

    @Test
    fun release_unloadsTheModelAndSpeakReloadsIt() = runBlocking {
        val speech = ButlerSpeech(context)
        assertTrue(speech.warmUp())
        assertTrue(speech.isLoaded)

        speech.release()
        assertFalse("release() must drop the ONNX session", speech.isLoaded)

        assertTrue("speak() should transparently reload after release()", speech.warmUp())
        speech.release()
    }
}
