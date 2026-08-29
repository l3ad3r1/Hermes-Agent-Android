package com.hermes.agent.core.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * The single store for Hermes' user-facing appearance settings.
 *
 * **Why SharedPreferences and not DataStore.** These are read synchronously during
 * composition — MainActivity reads the theme before the first frame — and DataStore
 * offers no synchronous read. Compose callers that want to observe changes get
 * [themeModeFlow] and friends, which bridge the change listener into a Flow.
 */
object HermesSettings {

    const val PREFS = "hermes_settings"

    // Theme
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_THEME_STYLE = "theme_style"
    const val KEY_FONT_FAMILY = "font_family"
    const val KEY_FONT_SCALE_PERCENT = "font_scale_percent"

    const val THEME_STYLE_CLASSIC = "classic"
    const val THEME_STYLE_MYBRAIN = "mybrain"
    const val THEME_STYLE_MATERIAL_YOU = "material_you"
    val THEME_STYLES: Set<String> =
        setOf(THEME_STYLE_CLASSIC, THEME_STYLE_MYBRAIN, THEME_STYLE_MATERIAL_YOU)

    const val FONT_GEIST = "geist"
    const val FONT_SYSTEM = "system"
    const val FONT_SERIF = "serif"
    const val FONT_MONO = "mono"
    const val FONT_RUBIK = "rubik"
    const val FONT_IBM_PLEX = "ibm_plex"
    val FONT_FAMILIES: Set<String> = setOf(FONT_GEIST, FONT_SYSTEM, FONT_SERIF, FONT_MONO, FONT_RUBIK, FONT_IBM_PLEX)
    const val DEFAULT_FONT_SCALE_PERCENT = 100
    const val MIN_FONT_SCALE_PERCENT = 85
    const val MAX_FONT_SCALE_PERCENT = 130

    fun prefs(context: Context): SharedPreferences {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    fun themeStyle(context: Context): String =
        prefs(context).getString(KEY_THEME_STYLE, THEME_STYLE_CLASSIC)
            ?.takeIf { it in THEME_STYLES }
            ?: THEME_STYLE_CLASSIC

    fun setThemeStyle(context: Context, style: String) =
        prefs(context).edit().putString(
            KEY_THEME_STYLE,
            style.takeIf { it in THEME_STYLES } ?: THEME_STYLE_CLASSIC,
        ).apply()

    fun themeStyleFlow(context: Context): Flow<String> = prefFlow(context) { p ->
        p.getString(KEY_THEME_STYLE, THEME_STYLE_CLASSIC)
            ?.takeIf { it in THEME_STYLES }
            ?: THEME_STYLE_CLASSIC
    }

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

}
