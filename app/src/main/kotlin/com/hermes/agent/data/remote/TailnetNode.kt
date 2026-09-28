package com.hermes.agent.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import timber.log.Timber
import tsbridge.InterfaceProvider
import tsbridge.Tsbridge
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
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

    /**
     * For the app's shared client: requests to a tailnet host (a MagicDNS `*.ts.net` name or a
     * 100.64.0.0/10 address -- the same rule the bridge's proxy enforces) go through the node
     * while it runs; everything else connects directly. This is what lets a cloud provider's
     * base URL point at a PC on the tailnet, e.g. the compute relay.
     */
    val tailnetProxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val proxy = if (isTailnetHost(uri.host)) proxyAddress() else null
            return listOf(proxy?.let { Proxy(Proxy.Type.HTTP, it) } ?: Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    /** Adds this run's proxy credential to tailnet-bound requests only; see [wrap]. */
    val tailnetProxyAuth = Interceptor { chain ->
        val request = chain.request()
        val token = if (isTailnetHost(request.url.host) && proxyAddress() != null) {
            runCatching { Tsbridge.proxyToken() }.getOrDefault("")
        } else {
            ""
        }
        chain.proceed(
            if (token.isBlank()) request
            else request.newBuilder().header("Proxy-Authorization", "Bearer $token").build(),
        )
    }

    private fun proxyAddress(): InetSocketAddress? {
        val address = runCatching { Tsbridge.proxyAddr() }.getOrDefault("")
        val port = address.substringAfterLast(':').toIntOrNull() ?: return null
        return InetSocketAddress.createUnresolved(address.substringBeforeLast(':'), port)
    }

    private fun defaultHostname(): String =
        ("hermes-" + android.os.Build.MODEL).lowercase().replace(Regex("[^a-z0-9-]"), "-").take(60)

    companion object {
        private const val TAG = "TailnetNode"
        private const val KEY_AUTOSTART = "autostart"
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** A MagicDNS name or an address in Tailscale's 100.64.0.0/10 range. No DNS lookup. */
        fun isTailnetHost(host: String?): Boolean {
            val h = host?.trimEnd('.')?.lowercase() ?: return false
            if (h.endsWith(".ts.net")) return true
            if (!IPV4.matches(h)) return false
            val octets = h.split('.').map { it.toInt() }
            return octets[0] == 100 && octets[1] in 64..127
        }
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
