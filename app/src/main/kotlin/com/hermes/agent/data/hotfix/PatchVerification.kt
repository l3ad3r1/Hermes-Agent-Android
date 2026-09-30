package com.hermes.agent.data.hotfix

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.jar.JarFile

/** TINKER_ID comparison: exact, and never true for a missing or malformed id. */
object TinkerIds {
    fun matches(installed: String?, patchBase: String?): Boolean =
        PatchManifest.isValidTinkerId(installed) && PatchManifest.isValidTinkerId(patchBase) && installed == patchBase
}

object PatchDigest {
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }

    /** Constant-time comparison of two hex digests (case-insensitive). */
    fun sameDigest(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.lowercase().toByteArray(), b.lowercase().toByteArray())

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Hermes' own signature check for a patch file, in addition to Tinker's.
 *
 * Tinker (ShareSecurityCheck) verifies only the `*_meta.txt` entries against the MD5 of the
 * installed signing certificate and trusts the MD5s listed in them for the rest. This requires
 * **every** non-META-INF entry to verify under the JAR signature and every signer certificate's
 * SHA-256 to be one of the installed app's signing certificates.
 */
object PatchSignatureVerifier {
    sealed class Result {
        data object Ok : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun verify(patch: File, allowedSignerSha256: Set<String>): Result {
        if (allowedSignerSha256.isEmpty()) return Result.Rejected("the installed app's signing certificate is unknown")
        val allowed = allowedSignerSha256.map { it.lowercase() }.toSet()
        return try {
            JarFile(patch, true).use { jar ->
                var signedEntries = 0
                val buf = ByteArray(64 * 1024)
                for (entry in jar.entries()) {
                    if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                    // Certificates are only known once the entry has been read to the end (that is
                    // when the digest is checked; a mismatch throws SecurityException).
                    jar.getInputStream(entry).use { while (it.read(buf) >= 0) Unit }
                    // getCertificates (what Tinker and Android's own verifier expose) rather than
                    // getCodeSigners. App signing keys are self-signed, so every certificate listed
                    // must itself be one of the app's: an extra signer or a chain is refused too.
                    val certs = entry.certificates
                    if (certs.isNullOrEmpty()) return Result.Rejected("unsigned entry ${entry.name}")
                    for (cert in certs) {
                        if (cert !is X509Certificate) return Result.Rejected("entry ${entry.name} has a non-X.509 signer")
                        val sha = PatchDigest.sha256Hex(cert.encoded)
                        if (sha !in allowed) return Result.Rejected("entry ${entry.name} is signed by an unknown certificate ${sha.take(8)}…")
                    }
                    signedEntries++
                }
                if (signedEntries == 0) Result.Rejected("patch has no signed content") else Result.Ok
            }
        } catch (e: SecurityException) {
            Result.Rejected("signature does not verify: ${e.message}")
        } catch (e: Exception) {
            Result.Rejected("not a readable signed archive: ${e.message}")
        }
    }

    /** `assets/package_meta.txt`, where tinker-patch-lib records TINKER_ID / NEW_TINKER_ID. */
    fun packageMeta(patch: File): Properties? = runCatching {
        JarFile(patch, false).use { jar ->
            val entry = jar.getJarEntry(PACKAGE_META) ?: return null
            Properties().apply { jar.getInputStream(entry).use { load(it) } }
        }
    }.getOrNull()

    const val PACKAGE_META = "assets/package_meta.txt"
}

/** What the OTA flow expects a downloaded patch to be (from its verified manifest). */
data class ExpectedPatch(
    val baseTinkerId: String,
    val patchVersion: Int,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * The trust gate every patch passes before Tinker sees it — run once when it is downloaded and
 * again by [com.hermes.agent.tinker.HermesPatchListener] when it is handed to Tinker:
 *
 * 1. exact size and SHA-256 of the manifest published with the release;
 * 2. the manifest's base TINKER_ID equals the installed build's, and so does the patch's own;
 * 3. every entry is signed by the installed app's signing certificate;
 * 4. the patch's own (signed) HERMES_PATCH_VERSION equals the manifest's patchVersion.
 *
 * Tinker then re-checks the signature and TINKER_ID itself, at install and on every load.
 */
object PatchGate {
    /** Written into the signed `assets/package_meta.txt` by tools/tinker/tinker_config.xml. */
    const val PATCH_VERSION_KEY = "HERMES_PATCH_VERSION"

    sealed class Verdict {
        data object Accepted : Verdict()
        data class Rejected(val reason: String) : Verdict()
    }

    fun verify(
        patch: File,
        expected: ExpectedPatch,
        installedTinkerId: String?,
        allowedSignerSha256: Set<String>,
    ): Verdict {
        if (!patch.isFile) return Verdict.Rejected("patch file missing")
        if (patch.length() != expected.sizeBytes) {
            return Verdict.Rejected("size ${patch.length()} does not match the manifest (${expected.sizeBytes})")
        }
        if (!PatchDigest.sameDigest(PatchDigest.sha256Hex(patch), expected.sha256)) {
            return Verdict.Rejected("SHA-256 does not match the manifest")
        }
        if (!TinkerIds.matches(installedTinkerId, expected.baseTinkerId)) {
            return Verdict.Rejected("made for build ${expected.baseTinkerId}, this is ${installedTinkerId ?: "unknown"}")
        }
        when (val sig = PatchSignatureVerifier.verify(patch, allowedSignerSha256)) {
            is PatchSignatureVerifier.Result.Rejected -> return Verdict.Rejected(sig.reason)
            PatchSignatureVerifier.Result.Ok -> Unit
        }
        val meta = PatchSignatureVerifier.packageMeta(patch)
            ?: return Verdict.Rejected("patch has no ${PatchSignatureVerifier.PACKAGE_META}")
        if (!TinkerIds.matches(installedTinkerId, meta.getProperty("TINKER_ID"))) {
            return Verdict.Rejected("patch's own TINKER_ID ${meta.getProperty("TINKER_ID")} is not this build's")
        }
        // The manifest is not signed, the patch is: bind the manifest's patchVersion to the signed
        // HERMES_PATCH_VERSION (tinker_config.xml packageConfig) so an old, legitimately signed patch
        // cannot be replayed under a higher number, or a removed/rolled-back one under a new number.
        val signedVersion = meta.getProperty(PATCH_VERSION_KEY)?.trim()?.toIntOrNull()
        if (signedVersion != expected.patchVersion) {
            return Verdict.Rejected("patch's own version ${meta.getProperty(PATCH_VERSION_KEY) ?: "(none)"} is not the manifest's ${expected.patchVersion}")
        }
        return Verdict.Accepted
    }
}
