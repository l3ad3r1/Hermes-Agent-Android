package com.hermes.agent.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The PC profiles ("bots") this phone knows about.
 *
 * The gateway has no endpoint that lists its profiles, so the names are kept here and edited
 * on the Bots screen. "default" is the unprefixed profile and is always present; the rest are
 * reached through the multiplexing gateway's `/p/<profile>/` prefix.
 */
@Singleton
class BotProfileStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences("bot_profiles", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(read())
    val profiles: StateFlow<List<String>> = _profiles.asStateFlow()

    /** Add [name], keeping the list ordered and free of duplicates. Invalid names are ignored. */
    fun add(name: String): Boolean {
        val cleaned = name.trim()
        if (!VALID.matches(cleaned) || cleaned in _profiles.value) return false
        write(_profiles.value + cleaned)
        return true
    }

    /** Remove [name]. The default profile cannot be removed — it is the gateway itself. */
    fun remove(name: String) {
        if (name == DEFAULT) return
        write(_profiles.value - name)
    }

    /**
     * The gateway the Chief last looked over for bots to offer, so it asks once per desktop
     * rather than every launch. Blank until it has.
     */
    var offeredGateway: String
        get() = prefs.getString(OFFERED_KEY, null).orEmpty()
        set(value) { prefs.edit().putString(OFFERED_KEY, value).apply() }

    /**
     * The bots the Chief has offered and is waiting on a yes or no for, or empty. Kept across
     * launches because the answer may come in a later session.
     */
    var openOffer: List<String>
        get() = prefs.getString(OFFER_KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        set(value) { prefs.edit().putString(OFFER_KEY, value.joinToString("\n")).apply() }

    private fun read(): List<String> {
        val stored = prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        return (listOf(DEFAULT) + stored.filter { it != DEFAULT }).distinct()
    }

    private fun write(names: List<String>) {
        val ordered = (listOf(DEFAULT) + names.filter { it != DEFAULT }).distinct()
        prefs.edit().putString(KEY, ordered.joinToString("\n")).apply()
        _profiles.value = ordered
    }

    companion object {
        const val DEFAULT = "default"
        private const val KEY = "profiles"
        private const val OFFERED_KEY = "offered_gateway"
        private const val OFFER_KEY = "open_offer"

        /** A profile name becomes a URL path segment, so nothing that could escape one. */
        val VALID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
