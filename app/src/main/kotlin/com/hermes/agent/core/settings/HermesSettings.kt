package com.hermes.agent.core.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * The single store for Jeeves' user-facing settings.
 *
 * Jotter and Butler are integrated parts of this app, not separate products, so their
 * preferences live here rather than in `butler_prefs`, `voice_prefs` and `theme_settings`.
 * One file, one settings screen, one migration.
 *
 * **Why SharedPreferences and not DataStore.** [ButlerPrefs][com.sassybutler.alarm.ButlerPrefs]
 * is read synchronously from `AlarmForegroundService` and `AudioEngine` while an alarm is
 * firing. DataStore offers no synchronous read; making the wake path async is a rewrite whose
 * failure mode is "the alarm didn't go off". Compose callers get [themeModeFlow] and friends,
 * which bridge the change listener into a Flow.
 *
 * Legacy values are migrated once, on first touch, by [ensureMigrated] — which every getter
 * calls, so no caller can observe a default that silently discarded the user's setting.
 * Alarm *data* (`alarms_store`) is not a setting and stays where it is.
 */
object HermesSettings {

    const val PREFS = "jeeves_settings"

    // Theme (was Jotter's `theme_settings` DataStore).
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_FONT_FAMILY = "font_family"
    const val KEY_FONT_SCALE_PERCENT = "font_scale_percent"

    const val FONT_GEIST = "geist"
    const val FONT_SYSTEM = "system"
    const val FONT_SERIF = "serif"
    const val FONT_MONO = "mono"
    val FONT_FAMILIES: Set<String> = setOf(FONT_GEIST, FONT_SYSTEM, FONT_SERIF, FONT_MONO)
    const val DEFAULT_FONT_SCALE_PERCENT = 100
    const val MIN_FONT_SCALE_PERCENT = 85
    const val MAX_FONT_SCALE_PERCENT = 130

    // Butler (was `butler_prefs` / `voice_prefs`).
    const val KEY_HONORIFIC = "honorific"
    const val KEY_SASS_LEVEL = "sass_level"
    const val KEY_SNOOZE_MINUTES = "snooze_minutes"
    const val KEY_BIRDS_INTRO = "birds_intro"
    const val KEY_VOICE_ENABLED = "voice_enabled"
    const val KEY_HAPTICS = "haptics"
    const val KEY_SNOOZE_COMMENTARY = "snooze_commentary"
    const val KEY_VOICE_NAME = "voice_name"

    // Briefing
    const val KEY_PRE_GENERATED_BRIEFING = "pre_generated_briefing"
    const val KEY_PRE_GENERATED_BRIEFING_TIMESTAMP = "pre_generated_briefing_timestamp"
    const val KEY_BRIEFING_CALENDAR = "briefing_calendar"
    const val KEY_BRIEFING_WEATHER = "briefing_weather"
    const val KEY_BRIEFING_TODOS = "briefing_todos"
    const val KEY_BRIEFING_NOTES = "briefing_notes"
    const val KEY_BRIEFING_HEADLINES = "briefing_headlines"

    /** Bumped if a future migration must run again. */
    private const val KEY_MIGRATED = "migrated_v1"

    // Defaults are the standalone apps' defaults, so nothing changes for a fresh install.
    const val DEFAULT_HONORIFIC = "Sir"
    const val DEFAULT_SASS_LEVEL = 45
    const val DEFAULT_SNOOZE_MINUTES = 10

    fun prefs(context: Context): SharedPreferences {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ensureMigrated(context.applicationContext, p)
        return p
    }

    // ─── Theme ──────────────────────────────────────────────────────────────

    fun themeMode(context: Context): String =
        prefs(context).getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeMode(context: Context, mode: String) =
        prefs(context).edit().putString(KEY_THEME_MODE, mode).apply()

    fun themeModeFlow(context: Context): Flow<String> =
        stringFlow(context, KEY_THEME_MODE, THEME_SYSTEM)

    /** True only if a theme was explicitly chosen — used by the one-time DataStore migration. */
    fun hasThemeMode(context: Context): Boolean = prefs(context).contains(KEY_THEME_MODE)

    fun fontFamily(context: Context): String =
        prefs(context).getString(KEY_FONT_FAMILY, FONT_GEIST)
            ?.takeIf { it in FONT_FAMILIES }
            ?: FONT_GEIST

    fun setFontFamily(context: Context, family: String) =
        prefs(context).edit().putString(
            KEY_FONT_FAMILY,
            family.takeIf { it in FONT_FAMILIES } ?: FONT_GEIST,
        ).apply()

    fun fontFamilyFlow(context: Context): Flow<String> = prefFlow(context) { p ->
        p.getString(KEY_FONT_FAMILY, FONT_GEIST)
            ?.takeIf { it in FONT_FAMILIES }
            ?: FONT_GEIST
    }

    fun fontScalePercent(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SCALE_PERCENT, DEFAULT_FONT_SCALE_PERCENT)
            .coerceIn(MIN_FONT_SCALE_PERCENT, MAX_FONT_SCALE_PERCENT)

    fun setFontScalePercent(context: Context, percent: Int) =
        prefs(context).edit().putInt(
            KEY_FONT_SCALE_PERCENT,
            percent.coerceIn(MIN_FONT_SCALE_PERCENT, MAX_FONT_SCALE_PERCENT),
        ).apply()

    fun fontScalePercentFlow(context: Context): Flow<Int> = prefFlow(context) { p ->
        p.getInt(KEY_FONT_SCALE_PERCENT, DEFAULT_FONT_SCALE_PERCENT)
            .coerceIn(MIN_FONT_SCALE_PERCENT, MAX_FONT_SCALE_PERCENT)
    }

    // ─── Butler ─────────────────────────────────────────────────────────────

    fun honorific(context: Context): String =
        prefs(context).getString(KEY_HONORIFIC, DEFAULT_HONORIFIC) ?: DEFAULT_HONORIFIC

    fun setHonorific(context: Context, value: String) =
        prefs(context).edit().putString(KEY_HONORIFIC, value).apply()

    fun sassLevel(context: Context): Int = prefs(context).getInt(KEY_SASS_LEVEL, DEFAULT_SASS_LEVEL)

    fun setSassLevel(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_SASS_LEVEL, value.coerceIn(0, 100)).apply()

    fun snoozeMinutes(context: Context): Int =
        prefs(context).getInt(KEY_SNOOZE_MINUTES, DEFAULT_SNOOZE_MINUTES)

    fun setSnoozeMinutes(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_SNOOZE_MINUTES, value).apply()

    fun birdsIntro(context: Context): Boolean = prefs(context).getBoolean(KEY_BIRDS_INTRO, true)

    fun setBirdsIntro(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BIRDS_INTRO, value).apply()

    fun voiceEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_VOICE_ENABLED, true)

    fun setVoiceEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_VOICE_ENABLED, value).apply()

    fun haptics(context: Context): Boolean = prefs(context).getBoolean(KEY_HAPTICS, false)

    fun setHaptics(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_HAPTICS, value).apply()

    fun snoozeCommentary(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SNOOZE_COMMENTARY, true)

    fun setSnoozeCommentary(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SNOOZE_COMMENTARY, value).apply()

    /** Voice id from Butler's catalog; the default lives there, so callers pass it in. */
    fun voiceName(context: Context, default: String): String =
        prefs(context).getString(KEY_VOICE_NAME, default) ?: default

    fun setVoiceName(context: Context, value: String) =
        prefs(context).edit().putString(KEY_VOICE_NAME, value).apply()

    // Briefing toggles & storage
    fun preGeneratedBriefing(context: Context): String? =
        prefs(context).getString(KEY_PRE_GENERATED_BRIEFING, null)

    fun setPreGeneratedBriefing(context: Context, value: String?) =
        prefs(context).edit().putString(KEY_PRE_GENERATED_BRIEFING, value).apply()

    fun preGeneratedBriefingTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_PRE_GENERATED_BRIEFING_TIMESTAMP, 0L)

    fun setPreGeneratedBriefingTimestamp(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_PRE_GENERATED_BRIEFING_TIMESTAMP, value).apply()

    fun briefingCalendar(context: Context): Boolean = prefs(context).getBoolean(KEY_BRIEFING_CALENDAR, true)
    fun setBriefingCalendar(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_BRIEFING_CALENDAR, value).apply()

    fun briefingWeather(context: Context): Boolean = prefs(context).getBoolean(KEY_BRIEFING_WEATHER, true)
    fun setBriefingWeather(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_BRIEFING_WEATHER, value).apply()

    fun briefingTodos(context: Context): Boolean = prefs(context).getBoolean(KEY_BRIEFING_TODOS, true)
    fun setBriefingTodos(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_BRIEFING_TODOS, value).apply()

    fun briefingNotes(context: Context): Boolean = prefs(context).getBoolean(KEY_BRIEFING_NOTES, true)
    fun setBriefingNotes(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_BRIEFING_NOTES, value).apply()

    fun briefingHeadlines(context: Context): Boolean = prefs(context).getBoolean(KEY_BRIEFING_HEADLINES, true)
    fun setBriefingHeadlines(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_BRIEFING_HEADLINES, value).apply()

    // ─── Flows for the settings UI ──────────────────────────────────────────

    fun stringFlow(context: Context, key: String, default: String): Flow<String> =
        prefFlow(context) { it.getString(key, default) ?: default }

    fun booleanFlow(context: Context, key: String, default: Boolean): Flow<Boolean> =
        prefFlow(context) { it.getBoolean(key, default) }

    fun intFlow(context: Context, key: String, default: Int): Flow<Int> =
        prefFlow(context) { it.getInt(key, default) }

    /**
     * Emits the current value, then again on every change to this file.
     *
     * The listener is registered against the same [SharedPreferences] instance we read from —
     * SharedPreferences holds listeners weakly, so keeping `p` in scope for the flow's lifetime
     * is what stops it being collected mid-collection.
     */
    private fun <T> prefFlow(context: Context, read: (SharedPreferences) -> T): Flow<T> =
        callbackFlow {
            val p = prefs(context)
            trySend(read(p))
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                trySend(read(p))
            }
            p.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { p.unregisterOnSharedPreferenceChangeListener(listener) }
        }.conflate()

    // ─── One-time migration off the legacy stores ───────────────────────────

    /**
     * Copies `butler_prefs` and `voice_prefs` into this store, once.
     *
     * Jotter's theme lived in a DataStore, which has no synchronous read, so it cannot be
     * migrated here; `ThemePreferences.migrateLegacyTheme()` does that from a coroutine and
     * guards on [hasThemeMode]. Everything else is SharedPreferences and migrates synchronously
     * on first touch, so no caller can read a default that discarded a real user setting.
     *
     * Only keys the user actually set are copied — `contains` checks, not getters with
     * defaults — otherwise a legacy default would be frozen in as an explicit choice.
     */
    @Synchronized
    private fun ensureMigrated(appContext: Context, p: SharedPreferences) {
        if (p.getBoolean(KEY_MIGRATED, false)) return

        val butler = appContext.getSharedPreferences("butler_prefs", Context.MODE_PRIVATE)
        val voice = appContext.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)
        val e = p.edit()

        if (butler.contains(KEY_HONORIFIC)) {
            e.putString(KEY_HONORIFIC, butler.getString(KEY_HONORIFIC, DEFAULT_HONORIFIC))
        }
        if (butler.contains(KEY_SASS_LEVEL)) {
            e.putInt(KEY_SASS_LEVEL, butler.getInt(KEY_SASS_LEVEL, DEFAULT_SASS_LEVEL))
        }
        if (butler.contains(KEY_SNOOZE_MINUTES)) {
            e.putInt(KEY_SNOOZE_MINUTES, butler.getInt(KEY_SNOOZE_MINUTES, DEFAULT_SNOOZE_MINUTES))
        }
        if (butler.contains(KEY_BIRDS_INTRO)) {
            e.putBoolean(KEY_BIRDS_INTRO, butler.getBoolean(KEY_BIRDS_INTRO, true))
        }
        if (butler.contains(KEY_VOICE_ENABLED)) {
            e.putBoolean(KEY_VOICE_ENABLED, butler.getBoolean(KEY_VOICE_ENABLED, true))
        }
        if (butler.contains(KEY_HAPTICS)) {
            e.putBoolean(KEY_HAPTICS, butler.getBoolean(KEY_HAPTICS, false))
        }
        if (butler.contains(KEY_SNOOZE_COMMENTARY)) {
            e.putBoolean(KEY_SNOOZE_COMMENTARY, butler.getBoolean(KEY_SNOOZE_COMMENTARY, true))
        }
        if (voice.contains(KEY_VOICE_NAME)) {
            voice.getString(KEY_VOICE_NAME, null)?.let { e.putString(KEY_VOICE_NAME, it) }
        }

        // commit(), not apply(): the very next line of a caller's getter reads this file, and a
        // crash between an async apply() and the read would silently reset the user's settings.
        e.putBoolean(KEY_MIGRATED, true).commit()
    }
}
