package com.hermes.agent.data.security

import okhttp3.CertificatePinner
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp [CertificatePinner] configuration for the cloud LLM endpoint.
 *
 * Per Section 8 of the plan: "encryption of all stored data at rest, and
 * verification of Samsung Knox integration" — Phase 4 also tightens
 * transport security by pinning the certificates of well-known
 * OpenAI-compatible endpoints.
 *
 * What this is:
 *   - A [CertificatePinner] preloaded with the SHA-256 hashes of the
 *     leaf certificates (and one intermediate) of the most common cloud
 *     LLM providers.
 *   - Used by [com.hermes.agent.di.NetworkModule] when constructing the
 *     OkHttpClient so any MITM attempt that presents a different
 *     certificate fails the TLS handshake.
 *
 * What this is NOT:
 *   - A replacement for the user's own PKI. Self-hosted vLLM / Ollama
 *     endpoints are exempt from pinning (they're not in the pin list).
 *   - A complete lock-down. Users can still configure a custom base URL
 *     in Settings; pinning only applies to the preloaded hosts.
 *
 * Rotation policy: when a provider rotates their certificate (which
 * OpenAI / Anthropic do annually), the new SHA-256 is added to the pin
 * list AND the old one is kept for a grace period. Phase 5 will move
 * the pin list to a remote-config endpoint so rotation doesn't require
 * an app update.
 *
 * The actual SHA-256 hashes below are placeholders — the real values
 * must be captured from a live TLS handshake to each provider. See
 * `docs/PHASE4.md` § "Capturing real certificate hashes".
 */
@Singleton
class CertificatePinningConfig @Inject constructor() {

    // Pinning is DISABLED until real certificate hashes are captured (Phase 4).
    // The previous placeholder pins (e.g. "sha256/PLACEHOLDER_OPENAI_LEAF=") are
    // not valid base64 SHA-256 values, so OkHttp throws IllegalArgumentException
    // the moment this object is constructed — crashing any screen whose Hilt
    // graph pulls in the network stack. An empty CertificatePinner is a safe
    // no-op: TLS still validates against the system trust store, just without
    // pinning. Re-enable by adding real `.add(host, "sha256/…")` lines below
    // once the hashes are captured per docs/PHASE4.md.
    val pinner: CertificatePinner = CertificatePinner.Builder()
        .build()

    init {
        Timber.tag("CertPinning").i(
            "CertificatePinner is a no-op (pinning disabled until real hashes are added — see docs/PHASE4.md)",
        )
    }

    companion object {
        /**
         * Hosts that are exempt from pinning because they're typically
         * self-hosted (vLLM, Ollama, llama.cpp server) and use self-signed
         * or local CA certificates.
         */
        val EXEMPT_HOSTS: Set<String> = setOf(
            "localhost",
            "10.0.2.2",  // Android emulator host loopback
            "127.0.0.1",
        )
    }
}
