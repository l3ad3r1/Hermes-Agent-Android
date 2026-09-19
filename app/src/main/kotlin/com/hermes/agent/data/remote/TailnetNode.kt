package com.hermes.agent.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import timber.log.Timber
import tsbridge.InterfaceProvider
import tsbridge.Tsbridge
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPIKE: an embedded Tailscale node, so the PC gateway is reachable without the Tailscale
 * app and without taking Android's single VPN slot.
 *
 * The node runs in userspace inside this process (tsnet, in `tsnet-bridge/`). Only calls
 * this app makes through [wrap] go over the tailnet; the rest of the phone is untouched.
 *
 * Android forbids apps from reading the routing table over netlink, so Go cannot enumerate
 * interfaces itself — [AndroidInterfaces] feeds it the list from Java instead, the same way
 * Tailscale's own Android app does.
 */
@Singleton
class TailnetNode @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("tailnet", Context.MODE_PRIVATE)

    @Volatile
    private var cached: Pair<String, OkHttpClient>? = null

    /** Start the node. Returns immediately; [status] then reports NeedsLogin or Running. */
    fun start(hostname: String = defaultHostname()) {
        val stateDir = java.io.File(context.filesDir, "tailnet").absolutePath
        Tsbridge.start(stateDir, hostname, AndroidInterfaces)
        prefs.edit().putBoolean(KEY_AUTOSTART, true).apply()
    }

    fun stop() {
        cached = null
        Tsbridge.stop()
        prefs.edit().putBoolean(KEY_AUTOSTART, false).apply()
    }

    /**
     * Bring the node back up on app launch if the user left it running. The node lives in this
     * process, so a restart (or crash) stops it, and the gateway's tailnet name then fails to
     * resolve until someone taps Start node again. A deliberate Stop node is remembered.
     */
    fun startIfEnabled() {
        if (prefs.getBoolean(KEY_AUTOSTART, false) && !status().running) start()
    }

    fun status(): TailnetStatus {
        val obj = runCatching { json.parseToJsonElement(Tsbridge.status()).jsonObject }.getOrElse {
            return TailnetStatus(running = false, state = "Stopped", error = it.message)
        }
        fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return TailnetStatus(
            running = obj["running"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            state = str("state") ?: "Stopped",
            authUrl = str("authURL"),
            hostname = str("hostname"),
            addresses = (obj["ips"] as? JsonArray)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            error = str("error"),
        )
    }

    fun logs(): String = Tsbridge.logs()

    /**
     * Return [client] routed through the running node, or [client] itself when the node is
     * stopped. The proxy is on loopback, which every app on the phone shares, so each run's
     * token is attached preemptively rather than waiting for a 407.
     */
    fun wrap(client: OkHttpClient): OkHttpClient {
        val address = runCatching { Tsbridge.proxyAddr() }.getOrElse {
            Timber.tag(TAG).w(it, "tailnet proxy unavailable")
            ""
        }
        if (address.isBlank()) return client
        cached?.let { (key, wrapped) -> if (key == address) return wrapped }

        val token = Tsbridge.proxyToken()
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: return client
        val wrapped = client.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)))
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Proxy-Authorization", "Bearer $token")
                        .build(),
                )
            }
            .build()
        cached = address to wrapped
        return wrapped
    }

    private fun defaultHostname(): String =
        ("hermes-" + android.os.Build.MODEL).lowercase().replace(Regex("[^a-z0-9-]"), "-").take(60)

    private companion object {
        const val TAG = "TailnetNode"
        const val KEY_AUTOSTART = "autostart"
    }
}

data class TailnetStatus(
    val running: Boolean,
    val state: String,
    val authUrl: String? = null,
    val hostname: String? = null,
    val addresses: List<String> = emptyList(),
    val error: String? = null,
)

/** Feeds Go the interface list Android will not let it read for itself. */
object AndroidInterfaces : InterfaceProvider {

    override fun interfacesJSON(): String = buildString {
        append('[')
        var first = true
        for (iface in NetworkInterface.getNetworkInterfaces().asSequence()) {
            if (!first) append(',')
            first = false
            append("{\"name\":\"").append(iface.name).append("\"")
            append(",\"index\":").append(iface.index)
            append(",\"mtu\":").append(runCatching { iface.mtu }.getOrDefault(1500))
            append(",\"up\":").append(runCatching { iface.isUp }.getOrDefault(false))
            append(",\"loopback\":").append(runCatching { iface.isLoopback }.getOrDefault(false))
            append(",\"pointToPoint\":").append(runCatching { iface.isPointToPoint }.getOrDefault(false))
            append(",\"multicast\":").append(runCatching { iface.supportsMulticast() }.getOrDefault(false))
            append(",\"addrs\":[")
            iface.interfaceAddresses.forEachIndexed { i, addr ->
                if (i > 0) append(',')
                append("\"").append(addr.address.hostAddress?.substringBefore('%'))
                append('/').append(addr.networkPrefixLength).append("\"")
            }
            append("]}")
        }
        append(']')
    }
}
