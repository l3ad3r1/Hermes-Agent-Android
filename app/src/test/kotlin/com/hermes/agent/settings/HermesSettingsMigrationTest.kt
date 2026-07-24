package com.hermes.agent.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.core.settings.HermesSettings
import com.sassybutler.alarm.ButlerPrefs
import com.sassybutler.alarm.VoiceCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The unified settings store must adopt a user's existing Butler preferences rather than
 * silently resetting them to defaults on upgrade — the failure mode is "my alarm voice and
 * snooze time reverted", which no build or type check would catch.
 *
 * Runs against a plain [Application], not `HermesApp`: the real application's `onCreate`
 * warms the settings store from a `Dispatchers.IO` coroutine, which would race this test's
 * legacy-store seeding and mark the migration done before it could run. That warm-up is
 * correct in production — there the legacy files already exist when it runs — but it has no
 * business inside a unit test of `HermesSettings`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HermesSettingsMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun legacyButler() = context.getSharedPreferences("butler_prefs", Context.MODE_PRIVATE)
    private fun legacyVoice() = context.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)
    private fun unified() = context.getSharedPreferences(HermesSettings.PREFS, Context.MODE_PRIVATE)

    @Before
    fun clearAllStores() {
        legacyButler().edit().clear().commit()
        legacyVoice().edit().clear().commit()
        unified().edit().clear().commit()
    }

    @Test
    fun `existing butler preferences survive the move to the unified store`() {
        legacyButler().edit()
            .putString("honorific", "Madam")
            .putInt("sass_level", 90)
            .putInt("snooze_minutes", 3)
            .putBoolean("birds_intro", false)
            .putBoolean("voice_enabled", false)
            .putBoolean("haptics", true)
            .putBoolean("snooze_commentary", false)
            .commit()
        legacyVoice().edit().putString("voice_name", "bf_emma").commit()

        // First touch through the public API triggers the migration.
        assertEquals("Madam", ButlerPrefs.honorific(context))
        assertEquals(90, ButlerPrefs.sassLevel(context))
        assertEquals(3, ButlerPrefs.snoozeMinutes(context))
        assertFalse(ButlerPrefs.birdsIntro(context))
        assertFalse(ButlerPrefs.voiceEnabled(context))
        assertTrue(ButlerPrefs.haptics(context))
        assertFalse(ButlerPrefs.snoozeCommentary(context))
        assertEquals("bf_emma", VoiceCatalog.selected(context))
    }

    @Test
    fun `a fresh install keeps the standalone apps' defaults`() {
        assertEquals("Sir", ButlerPrefs.honorific(context))
        assertEquals(45, ButlerPrefs.sassLevel(context))
        assertEquals(10, ButlerPrefs.snoozeMinutes(context))
        assertTrue(ButlerPrefs.birdsIntro(context))
        assertTrue(ButlerPrefs.voiceEnabled(context))
        assertFalse(ButlerPrefs.haptics(context))
        assertTrue(ButlerPrefs.snoozeCommentary(context))
    }

    /**
     * Only keys the user actually set may be copied. Copying via getters-with-defaults would
     * freeze a legacy default in as an explicit choice, so a later change to that default
     * would not reach existing users.
     */
    @Test
    fun `unset legacy keys are not written into the unified store`() {
        legacyButler().edit().putString("honorific", "Boss").commit()

        assertEquals("Boss", ButlerPrefs.honorific(context))
        assertFalse(
            "sass_level was never set by the user and must not be materialised",
            unified().contains(HermesSettings.KEY_SASS_LEVEL),
        )
        assertEquals("default still applies", 45, ButlerPrefs.sassLevel(context))
    }

    @Test
    fun `migration runs once and never clobbers a newer value`() {
        legacyButler().edit().putString("honorific", "Madam").commit()
        assertEquals("Madam", ButlerPrefs.honorific(context))   // migrates

        ButlerPrefs.setHonorific(context, "Boss")               // user changes it in Jeeves
        legacyButler().edit().putString("honorific", "Sir").commit() // stale legacy write

        assertEquals("Boss", ButlerPrefs.honorific(context))
    }

    @Test
    fun `writes through ButlerPrefs land in the unified store, not the legacy file`() {
        ButlerPrefs.setSnoozeMinutes(context, 7)

        assertEquals(7, unified().getInt(HermesSettings.KEY_SNOOZE_MINUTES, -1))
        assertFalse(legacyButler().contains("snooze_minutes"))
    }

    @Test
    fun `sass level stays clamped to 0-100 through the delegate`() {
        ButlerPrefs.setSassLevel(context, 500)
        assertEquals(100, ButlerPrefs.sassLevel(context))
        ButlerPrefs.setSassLevel(context, -20)
        assertEquals(0, ButlerPrefs.sassLevel(context))
    }

    @Test
    fun `theme flow emits the current value and then updates`() = runTest {
        HermesSettings.setThemeMode(context, HermesSettings.THEME_DARK)
        assertEquals(HermesSettings.THEME_DARK, HermesSettings.themeModeFlow(context).first())
    }

    @Test
    fun `theme defaults to system when never chosen`() = runTest {
        assertFalse(HermesSettings.hasThemeMode(context))
        assertEquals(HermesSettings.THEME_SYSTEM, HermesSettings.themeMode(context))
    }

    /**
     * "Same settings": Butler's own preferences sheet writes through ButlerPrefs, while the
     * unified Hermes settings screen reads/writes HermesSettings. If those ever addressed
     * different storage the two surfaces would silently disagree.
     */
    @Test
    fun `Butler's sheet and the unified settings screen read and write the same values`() {
        // Butler's sheet writes...
        ButlerPrefs.setSassLevel(context, 77)
        ButlerPrefs.setVoiceEnabled(context, false)
        VoiceCatalog.select(context, "bm_lewis")

        // ...the Hermes settings screen sees it.
        assertEquals(77, HermesSettings.sassLevel(context))
        assertFalse(HermesSettings.voiceEnabled(context))
        assertEquals("bm_lewis", HermesSettings.voiceName(context, "bm_george"))

        // And the reverse: the settings screen writes...
        HermesSettings.setSnoozeMinutes(context, 21)
        HermesSettings.setHonorific(context, "Madam")

        // ...Butler's alarm path sees it.
        assertEquals(21, ButlerPrefs.snoozeMinutes(context))
        assertEquals("Madam", ButlerPrefs.honorific(context))
    }

    @Test
    fun `appearance font settings persist and flow from the unified store`() = runTest {
        HermesSettings.setFontFamily(context, HermesSettings.FONT_SERIF)
        HermesSettings.setFontScalePercent(context, 115)

        assertEquals(HermesSettings.FONT_SERIF, HermesSettings.fontFamily(context))
        assertEquals(HermesSettings.FONT_SERIF, HermesSettings.fontFamilyFlow(context).first())
        assertEquals(115, HermesSettings.fontScalePercent(context))
        assertEquals(115, HermesSettings.fontScalePercentFlow(context).first())
    }

    @Test
    fun `appearance settings normalise invalid fonts and clamp font size`() {
        HermesSettings.setFontFamily(context, "comic-sans")
        HermesSettings.setFontScalePercent(context, 500)

        assertEquals(HermesSettings.FONT_GEIST, HermesSettings.fontFamily(context))
        assertEquals(HermesSettings.MAX_FONT_SCALE_PERCENT, HermesSettings.fontScalePercent(context))

        HermesSettings.setFontScalePercent(context, -20)
        assertEquals(HermesSettings.MIN_FONT_SCALE_PERCENT, HermesSettings.fontScalePercent(context))
    }}
