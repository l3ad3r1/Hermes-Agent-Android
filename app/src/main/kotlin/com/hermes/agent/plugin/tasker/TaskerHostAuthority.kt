package com.hermes.agent.plugin.tasker

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which automation apps may drive the agent through the Tasker plugin.
 *
 * ## Why this exists rather than a permission
 *
 * The receiver used to be guarded by a `signature`-level custom permission.
 * That is airtight and also useless: Tasker is signed by its own publisher, so
 * the only app that could ever satisfy it was Hermes itself. The integration
 * could not work as shipped.
 *
 * ## Why a token rather than checking the caller
 *
 * A [android.content.BroadcastReceiver] cannot learn who sent it a broadcast —
 * Android carries no sender identity to `onReceive`, so there is nothing to
 * check at fire time. The identity is only available at *configuration* time:
 * Tasker starts [TaskerPluginActivity] with `startActivityForResult`, which
 * makes `callingActivity` available and unspoofable.
 *
 * So the trust decision happens there. When the user approves a host, this
 * class records the host's package **and the SHA-256 of its signing
 * certificate**, then mints a random token that goes into the plugin
 * configuration Tasker persists. At fire time the token in the bundle is the
 * proof of that earlier, user-approved handshake.
 *
 * ## What this does and does not stop
 *
 * It stops any app from firing the receiver blind, which is the whole of the
 * current exposure. It does not stop an attacker who can already read another
 * app's private Tasker configuration — but an attacker at that level has root
 * or an OS bug, and every other guard in the app has fallen too. Recording the
 * signing certificate means a reinstall of the host by a *different* signer
 * (the realistic package-name-squatting attack) invalidates the approval.
 */
@Singleton
class TaskerHostAuthority @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class ApprovedHost(
        val packageName: String,
        val label: String,
        val signingSha256: String,
        val approvedAtMillis: Long,
    )

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** SHA-256 of [packageName]'s current signing certificate, or null if not installed. */
    fun signingCertificate(packageName: String): String? = try {
        val bytes = modernSignature(packageName) ?: legacySignature(packageName)
        bytes?.let {
            MessageDigest.getInstance("SHA-256").digest(it)
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "Could not read the signing certificate of %s", packageName)
        null
    }

    /** The API 28+ path. `signingInfo` is nullable, hence the fallback below. */
    private fun modernSignature(packageName: String): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val info = context.packageManager
            .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return null
        val certificates =
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return certificates?.firstOrNull()?.toByteArray()
    }

    @Suppress("DEPRECATION")
    private fun legacySignature(packageName: String): ByteArray? =
        context.packageManager
            .getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            .signatures
            ?.firstOrNull()
            ?.toByteArray()

    /** Human-readable name for [packageName], falling back to the package itself. */
    fun label(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (t: Throwable) {
        packageName
    }

    /**
     * Records the user's approval of [packageName] and returns the token that
     * must accompany every fire from it. Re-approving mints a fresh token,
     * which invalidates any configuration made under the previous one.
     */
    fun approve(packageName: String): String? {
        val certificate = signingCertificate(packageName) ?: return null
        val token = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
            .joinToString("") { "%02x".format(it) }
        prefs.edit()
            .putString(keyCert(packageName), certificate)
            .putString(keyToken(packageName), token)
            .putLong(keyApprovedAt(packageName), System.currentTimeMillis())
            .apply()
        Timber.tag(TAG).i("Approved automation host %s", packageName)
        return token
    }

    fun revoke(packageName: String) {
        prefs.edit()
            .remove(keyCert(packageName))
            .remove(keyToken(packageName))
            .remove(keyApprovedAt(packageName))
            .apply()
        Timber.tag(TAG).i("Revoked automation host %s", packageName)
    }

    /**
     * Whether [packageName] is approved *and* still signed by the certificate
     * that was approved. A mismatch means the package was replaced by a build
     * from a different signer, so the approval no longer refers to that app.
     */
    fun isApproved(packageName: String): Boolean {
        val approvedCert = prefs.getString(keyCert(packageName), null) ?: return false
        val currentCert = signingCertificate(packageName) ?: return false
        return approvedCert == currentCert
    }

    /** The live token for an approved host, or null if it is not approved. */
    fun tokenFor(packageName: String): String? =
        if (isApproved(packageName)) prefs.getString(keyToken(packageName), null) else null

    /**
     * Resolves a token presented at fire time back to the host that owns it,
     * re-checking the signing certificate. Returns null when the token is
     * unknown, blank, or the owning app has been replaced by another signer.
     */
    fun hostForToken(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val owner = prefs.all.entries
            .firstOrNull { (key, value) -> key.startsWith(PREFIX_TOKEN) && value == token }
            ?.key
            ?.removePrefix(PREFIX_TOKEN)
            ?: return null
        return if (isApproved(owner)) owner else null
    }

    fun approvedHosts(): List<ApprovedHost> =
        prefs.all.keys
            .filter { it.startsWith(PREFIX_CERT) }
            .map { it.removePrefix(PREFIX_CERT) }
            .mapNotNull { pkg ->
                val cert = prefs.getString(keyCert(pkg), null) ?: return@mapNotNull null
                ApprovedHost(
                    packageName = pkg,
                    label = label(pkg),
                    signingSha256 = cert,
                    approvedAtMillis = prefs.getLong(keyApprovedAt(pkg), 0L),
                )
            }
            .sortedBy { it.label.lowercase() }

    private fun keyCert(pkg: String) = PREFIX_CERT + pkg
    private fun keyToken(pkg: String) = PREFIX_TOKEN + pkg
    private fun keyApprovedAt(pkg: String) = PREFIX_APPROVED_AT + pkg

    private companion object {
        const val TAG = "TaskerHosts"
        const val PREFS_NAME = "hermes_tasker_hosts"
        const val PREFIX_CERT = "cert:"
        const val PREFIX_TOKEN = "token:"
        const val PREFIX_APPROVED_AT = "approvedAt:"
        const val TOKEN_BYTES = 32
        val secureRandom = SecureRandom()
    }
}
