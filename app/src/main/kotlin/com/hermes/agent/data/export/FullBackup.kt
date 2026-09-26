package com.hermes.agent.data.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.Settings
import android.util.Log
import com.hermes.agent.BuildConfig
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.data.memory.LearningState
import com.hermes.agent.data.settings.SettingsRepositoryImpl
import com.hermes.agent.domain.backup.BackupStreamCipher
import com.hermes.agent.domain.backup.RawPref
import com.hermes.agent.domain.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a full backup is made of and where each part goes.
 *
 * "Everything" here is decided by storage location, not by a list of features. The whole database,
 * the whole settings store, every preferences file and every file the app keeps are taken unless
 * they are named in one of the small exclusion lists below, so something added next month is
 * carried without anyone remembering to add it. The exclusions are the exceptions, each with a
 * reason.
 */
object FullBackupFormat {
    const val FORMAT = 1
    const val MANIFEST = "manifest.json"
    const val SUMMARY = "summary.json"
    const val SETTINGS = "settings/hermes_settings.json"
    const val LEARNING = "settings/hermes_learning_state.json"
    const val PREFS_DIR = "prefs/"
    const val DATABASE = "database/hermes.db"
    const val FILES_DIR = "files/"
    const val EXTERNAL_DIR = "external/"

    internal const val PENDING_DIR = "pending-restore"
    internal const val READY = "READY"
    internal const val KEPT_DATABASE_SUFFIX = ".pre-restore"

    /** Top-level entries of the private files directory that are not user data. */
    val FILES_SKIPPED = setOf(
        "datastore", // written through the settings API instead, so secrets can be re-sealed
        "hermes.log", // a diagnostic log, not state
        "crash", // an unsent crash report, not state
        "profileInstalled", // a marker Android writes
        "models", // downloaded models: gigabytes, and downloaded again from the catalog
        PENDING_DIR, // a restore in progress
    )

    /** Preference files that belong to Android or a library rather than to Hermes. */
    private val PREFS_SKIPPED_PREFIXES = listOf("androidx.", "com.google", "WebView", "android.app", "full_backup", "auto_backup")

    fun isHermesPrefs(name: String): Boolean = PREFS_SKIPPED_PREFIXES.none { name.startsWith(it) }

    /** Files inside the Tailscale node's directory that are logs or scratch, not identity. */
    fun isTailnetNoise(relative: String): Boolean =
        relative.startsWith("tailnet/tmp/") || relative.startsWith("tailnet/cache/") ||
            relative.contains("/netmap-cache/") || Regex(""".*tailscaled\.log\d*\.txt$""").matches(relative)

    /**
     * True for state that identifies this one device on the tailnet. Restoring it onto a different
     * phone would put two devices on one node key, so it is only applied where it came from.
     */
    fun isDeviceBound(entryName: String): Boolean =
        entryName.startsWith("${FILES_DIR}tailnet/") || entryName == "${PREFS_DIR}tailnet.json"
}

@Serializable
data class FullBackupManifest(
    val format: Int = FullBackupFormat.FORMAT,
    val app: String = "hermes",
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val exportedAt: Long = 0L,
    /** The database schema version, so a backup from a newer app is refused rather than half-read. */
    val dbVersion: Int = 0,
    /** A hash of this device's id, to tell "the same phone" from "another one". */
    val deviceId: String = "",
)

@Serializable
data class FullBackupSummary(
    val entries: List<String> = emptyList(),
    val settings: Int = 0,
    val prefsFiles: Int = 0,
    val files: Int = 0,
    val databaseBytes: Long = 0L,
    val skipped: List<String> = emptyList(),
)

/** A backup that has been decrypted, checked and put aside, waiting for the next launch. */
data class StagedRestore(
    val manifest: FullBackupManifest,
    val summary: FullBackupSummary,
    val sameDevice: Boolean,
)

/** What the launch that applied a staged restore did, kept so the app can say so. */
@Serializable
data class RestoreResult(
    val ok: Boolean,
    val at: Long,
    val message: String,
    val problems: List<String> = emptyList(),
)

private val rawMap = MapSerializer(String.serializer(), RawPref.serializer())
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

@Singleton
class FullBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: HermesDatabase,
    private val settings: SettingsRepository,
    private val learning: LearningState,
) {

    /**
     * Writes everything to [out], encrypted with [password]. Fails loudly instead of skipping
     * something: a backup that quietly leaves a part out is the thing this exists to prevent.
     */
    suspend fun backup(out: OutputStream, password: String): FullBackupSummary = withContext(Dispatchers.IO) {
        val databaseCopy = File(context.cacheDir, "full-backup-database.tmp")
        try {
            snapshotDatabase(databaseCopy)
            val entries = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            var prefsCount = 0
            var fileCount = 0
            var settingsCount = 0
            var summary: FullBackupSummary? = null

            BackupStreamCipher.encrypt(out, password).use { encrypted ->
                ZipOutputStream(encrypted).use { zip ->
                    fun entry(name: String, write: (OutputStream) -> Unit) {
                        zip.putNextEntry(ZipEntry(name))
                        write(zip)
                        zip.closeEntry()
                        entries += name
                    }

                    entry(FullBackupFormat.MANIFEST) {
                        it.write(
                            json.encodeToString(
                                FullBackupManifest.serializer(),
                                FullBackupManifest(
                                    appVersionName = BuildConfig.VERSION_NAME,
                                    appVersionCode = BuildConfig.VERSION_CODE,
                                    exportedAt = System.currentTimeMillis(),
                                    dbVersion = currentDatabaseVersion(),
                                    deviceId = DeviceId.of(context),
                                ),
                            ).toByteArray(),
                        )
                    }

                    val settingsMap = settings.exportRawPreferences()
                    settingsCount = settingsMap.size
                    entry(FullBackupFormat.SETTINGS) { it.write(json.encodeToString(rawMap, settingsMap).toByteArray()) }
                    val learningMap = learning.exportRaw()
                    entry(FullBackupFormat.LEARNING) { it.write(json.encodeToString(rawMap, learningMap).toByteArray()) }

                    for (name in prefsNames()) {
                        entry("${FullBackupFormat.PREFS_DIR}$name.json") {
                            it.write(json.encodeToString(rawMap, PrefsFiles.dump(context, name)).toByteArray())
                        }
                        prefsCount++
                    }

                    entry(FullBackupFormat.DATABASE) { out2 -> databaseCopy.inputStream().use { it.copyTo(out2) } }

                    fileCount += addTree(zip, entries, skipped, context.filesDir, FullBackupFormat.FILES_DIR) { relative ->
                        val top = relative.substringBefore('/')
                        top in FullBackupFormat.FILES_SKIPPED || FullBackupFormat.isTailnetNoise(relative)
                    }
                    val workspace = context.getExternalFilesDir(null)?.resolve("workspace")
                    if (workspace != null && workspace.isDirectory) {
                        fileCount += addTree(zip, entries, skipped, workspace, "${FullBackupFormat.EXTERNAL_DIR}workspace/") { false }
                    }
                    if (File(context.filesDir, "models").exists()) {
                        skipped += "models/ (downloaded models: download them again)"
                    }

                    val done = FullBackupSummary(
                        entries = entries + FullBackupFormat.SUMMARY,
                        settings = settingsCount,
                        prefsFiles = prefsCount,
                        files = fileCount,
                        databaseBytes = databaseCopy.length(),
                        skipped = skipped,
                    )
                    zip.putNextEntry(ZipEntry(FullBackupFormat.SUMMARY))
                    zip.write(json.encodeToString(FullBackupSummary.serializer(), done).toByteArray())
                    zip.closeEntry()
                    summary = done
                }
            }
            checkNotNull(summary)
        } finally {
            databaseCopy.delete()
        }
    }

    /**
     * Reads a backup, checks all of it, and puts it aside to be applied on the next launch.
     *
     * Nothing about the running app changes here. That is deliberate: replacing the database under
     * screens that are reading it would crash them, so the swap waits for a start-up before anything
     * has opened it. Because the whole file is decrypted and verified first, a wrong password or a
     * damaged file is reported now and leaves the app exactly as it was.
     */
    suspend fun stageRestore(input: InputStream, password: String): StagedRestore = withContext(Dispatchers.IO) {
        val staging = File(context.filesDir, FullBackupFormat.PENDING_DIR)
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            var manifest: FullBackupManifest? = null
            var summary: FullBackupSummary? = null
            BackupStreamCipher.decrypt(input, password).use { decrypted ->
                ZipInputStream(decrypted).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val target = safeTarget(staging, name)
                        if (!entry.isDirectory) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                            when (name) {
                                FullBackupFormat.MANIFEST -> {
                                    manifest = json.decodeFromString(FullBackupManifest.serializer(), target.readText())
                                }
                                FullBackupFormat.SUMMARY -> {
                                    summary = json.decodeFromString(FullBackupSummary.serializer(), target.readText())
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            val m = manifest ?: throw BackupStreamCipher.CorruptBackupException("This backup has no manifest.")
            val s = summary ?: throw BackupStreamCipher.CorruptBackupException("This backup is incomplete: it has no summary.")
            if (m.format > FullBackupFormat.FORMAT) {
                throw IOException("This backup was made by a newer version of Hermes (format ${m.format}). Update Hermes and try again.")
            }
            val current = currentDatabaseVersion()
            if (m.dbVersion > current) {
                throw IOException("This backup's data is newer than this version of Hermes understands. Update Hermes and try again.")
            }
            val missing = s.entries.filterNot { File(staging, it).isFile }
            if (missing.isNotEmpty()) {
                throw BackupStreamCipher.CorruptBackupException("This backup is incomplete: ${missing.first()} is missing.")
            }
            DatabaseFiles.requireIntact(File(staging, FullBackupFormat.DATABASE), current)
            // Decoded now so a bad file is reported here, not discovered halfway through applying it.
            json.decodeFromString(rawMap, File(staging, FullBackupFormat.SETTINGS).readText())
            json.decodeFromString(rawMap, File(staging, FullBackupFormat.LEARNING).readText())

            val staged = StagedRestore(m, s, sameDevice = m.deviceId == DeviceId.of(context))
            File(staging, FullBackupFormat.READY).writeText(
                json.encodeToString(FullBackupManifest.serializer(), m),
            )
            staged
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    /** True while a restore is waiting for the next launch. */
    fun isRestorePending(): Boolean =
        File(File(context.filesDir, FullBackupFormat.PENDING_DIR), FullBackupFormat.READY).isFile

    /** Drops a staged restore that has not been applied. */
    fun cancelPendingRestore() {
        File(context.filesDir, FullBackupFormat.PENDING_DIR).deleteRecursively()
    }

    /** The outcome of the last launch that applied a restore, until it is dismissed. */
    fun lastRestoreResult(): RestoreResult? = PendingRestore.lastResult(context)

    fun clearRestoreResult() = PendingRestore.clearResult(context)

    private fun currentDatabaseVersion(): Int = database.openHelper.readableDatabase.version

    /**
     * A consistent copy of the live database. The write-ahead log is folded into the main file
     * first, and the copy is opened and integrity-checked, because a copy taken while a write lands
     * can be torn.
     */
    private fun snapshotDatabase(target: File) {
        val source = context.getDatabasePath(HermesDatabase.DATABASE_NAME)
        repeat(3) {
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            source.copyTo(target, overwrite = true)
            if (DatabaseFiles.isIntact(target)) return
        }
        throw IOException("Could not take a consistent copy of the database. Try again.")
    }

    private fun prefsNames(): List<String> {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.filter { FullBackupFormat.isHermesPrefs(it) }
            ?.sorted()
            .orEmpty()
    }

    /** Adds every regular file under [root]; returns how many. Symlinks are never followed. */
    private fun addTree(
        zip: ZipOutputStream,
        entries: MutableList<String>,
        skipped: MutableList<String>,
        root: File,
        prefix: String,
        skip: (relative: String) -> Boolean,
    ): Int {
        var count = 0
        root.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (skip(relative)) return@forEach
                val name = prefix + relative
                val stream = try {
                    file.inputStream()
                } catch (e: IOException) {
                    skipped += "$name (unreadable: ${e.message})"
                    return@forEach
                }
                stream.use {
                    zip.putNextEntry(ZipEntry(name))
                    it.copyTo(zip)
                    zip.closeEntry()
                }
                entries += name
                count++
            }
        return count
    }

    private fun safeTarget(root: File, entryName: String): File {
        val target = File(root, entryName).canonicalFile
        val base = root.canonicalFile
        // A crafted entry name must not be able to write outside the staging directory.
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            throw BackupStreamCipher.CorruptBackupException("This backup contains an unsafe path.")
        }
        return target
    }
}

/** Reads and writes a SharedPreferences file as plain, typed data. */
internal object PrefsFiles {

    fun dump(context: Context, name: String): Map<String, RawPref> =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            val raw = when (value) {
                is Boolean -> RawPref("boolean", kotlinx.serialization.json.JsonPrimitive(value))
                is Int -> RawPref("int", kotlinx.serialization.json.JsonPrimitive(value))
                is Long -> RawPref("long", kotlinx.serialization.json.JsonPrimitive(value))
                is Float -> RawPref("float", kotlinx.serialization.json.JsonPrimitive(value))
                is String -> RawPref("string", kotlinx.serialization.json.JsonPrimitive(value))
                is Set<*> -> RawPref(
                    "stringSet",
                    kotlinx.serialization.json.JsonArray(value.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) }),
                )
                else -> null
            }
            raw?.let { key to it }
        }.toMap()

    /** Replaces the whole file with [entries]. Returns names of entries of an unknown type. */
    fun replace(context: Context, name: String, entries: Map<String, RawPref>): List<String> {
        val skipped = mutableListOf<String>()
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        for ((key, pref) in entries) {
            val v = pref.value
            when (pref.type) {
                "boolean" -> editor.putBoolean(key, v.jsonPrimitiveBoolean())
                "int" -> editor.putInt(key, v.jsonPrimitiveContent().toInt())
                "long" -> editor.putLong(key, v.jsonPrimitiveContent().toLong())
                "float" -> editor.putFloat(key, v.jsonPrimitiveContent().toFloat())
                "string" -> editor.putString(key, v.jsonPrimitiveContent())
                "stringSet" -> editor.putStringSet(
                    key,
                    (v as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitiveContent() }.toSet(),
                )
                else -> skipped += key
            }
        }
        // commit(), not apply(): the result must be on disk before the app carries on starting.
        editor.commit()
        return skipped
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent() =
        (this as kotlinx.serialization.json.JsonPrimitive).content

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveBoolean() =
        jsonPrimitiveContent().toBooleanStrict()
}

internal object DatabaseFiles {

    /**
     * Opens a standalone copy of a database, which must be writable.
     *
     * A copy of a write-ahead-log database still says it is one, and Android's SQLite cannot open
     * such a file read-only without its `-shm` beside it (the desktop SQLite in unit tests can).
     * So the copy is opened read-write and switched to the plain rollback journal: it then stands on
     * its own, which is also what a backup file should be. Room turns the log back on when the
     * restored database is opened.
     */
    private fun <T> withStandaloneCopy(file: File, block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            block(db)
        }

    /** True if [file] opens as a SQLite database and passes SQLite's own integrity check. */
    fun isIntact(file: File): Boolean = runCatching {
        withStandaloneCopy(file) { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
        }
    }.getOrDefault(false)

    fun requireIntact(file: File, maxVersion: Int) {
        val ok = runCatching {
            withStandaloneCopy(file) { db ->
                if (db.version > maxVersion) {
                    throw IOException("This backup's database is newer than this version of Hermes understands.")
                }
                db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
            }
        }
        val failure = ok.exceptionOrNull()
        if (failure is IOException) throw failure
        if (ok.getOrDefault(false).not()) {
            throw BackupStreamCipher.CorruptBackupException("The database inside this backup is damaged.")
        }
    }
}

internal object DeviceId {
    /** A stable hash of this device's id, never the id itself, so the backup does not carry it. */
    fun of(context: Context): String {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}

/**
 * Applies a staged restore. Runs at the start of the next launch, before anything has opened the
 * database or read a setting, so nothing is racing it.
 *
 * The database swap comes first and is a rename, so it either happens completely or not at all; the
 * old database is kept beside it as `hermes.db.pre-restore`. Every later step is best-effort and
 * reported, because by then the main part is done and stopping would leave it half-restored.
 */
object PendingRestore {

    private const val TAG = "PendingRestore"
    private const val STATE_PREFS = "full_backup_state"
    private const val KEY_RESULT = "last_result"

    fun applyIfPending(context: Context) {
        val dir = File(context.filesDir, FullBackupFormat.PENDING_DIR)
        if (!dir.exists()) return
        if (!File(dir, FullBackupFormat.READY).isFile) {
            // Staging that never finished: nothing to apply, and keeping it wastes space.
            dir.deleteRecursively()
            return
        }
        val result = try {
            apply(context, dir)
        } catch (t: Throwable) {
            Log.e(TAG, "restore failed; the app keeps its current data", t)
            RestoreResult(false, System.currentTimeMillis(), "The restore could not be applied, so nothing was changed: ${t.message}")
        }
        dir.deleteRecursively()
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RESULT, json.encodeToString(RestoreResult.serializer(), result)).commit()
    }

    fun lastResult(context: Context): RestoreResult? =
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getString(KEY_RESULT, null)
            ?.let { runCatching { json.decodeFromString(RestoreResult.serializer(), it) }.getOrNull() }

    fun clearResult(context: Context) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RESULT).commit()
    }

    private fun apply(context: Context, dir: File): RestoreResult {
        val manifest = json.decodeFromString(FullBackupManifest.serializer(), File(dir, FullBackupFormat.READY).readText())
        val sameDevice = manifest.deviceId == DeviceId.of(context)
        val problems = mutableListOf<String>()

        // 1. The database. Throws on failure, and nothing else has been touched yet.
        swapDatabase(context, File(dir, FullBackupFormat.DATABASE))

        // 2. Settings (credentials are sealed again for this install) and learning state.
        runCatching {
            val entries = json.decodeFromString(rawMap, File(dir, FullBackupFormat.SETTINGS).readText())
            val skipped = runBlocking { SettingsRepositoryImpl(context).importRawPreferences(entries) }
            if (skipped.isNotEmpty()) problems += "Settings this version does not know were left out: ${skipped.joinToString()}"
        }.onFailure { problems += "Settings: ${it.message}" }
        runCatching {
            val entries = json.decodeFromString(rawMap, File(dir, FullBackupFormat.LEARNING).readText())
            runBlocking { LearningState(context).importRaw(entries) }
        }.onFailure { problems += "Learning state: ${it.message}" }

        // 3. Preference files.
        var prefs = 0
        File(dir, FullBackupFormat.PREFS_DIR).listFiles { f -> f.name.endsWith(".json") }?.forEach { file ->
            val entryName = FullBackupFormat.PREFS_DIR + file.name
            if (FullBackupFormat.isDeviceBound(entryName) && !sameDevice) return@forEach
            runCatching {
                PrefsFiles.replace(context, file.name.removeSuffix(".json"), json.decodeFromString(rawMap, file.readText()))
                prefs++
            }.onFailure { problems += "${file.name}: ${it.message}" }
        }

        // 4. Files: added or overwritten, never deleted, so nothing made since the backup is lost.
        var files = 0
        var boundSkipped = 0
        files += copyTree(File(dir, FullBackupFormat.FILES_DIR), context.filesDir, problems) { relative ->
            val bound = FullBackupFormat.isDeviceBound(FullBackupFormat.FILES_DIR + relative)
            if (bound && !sameDevice) boundSkipped++
            !bound || sameDevice
        }
        val externalRoot = File(dir, FullBackupFormat.EXTERNAL_DIR)
        if (externalRoot.isDirectory) {
            val external = context.getExternalFilesDir(null)
            if (external != null) {
                files += copyTree(externalRoot, external, problems) { true }
            } else {
                problems += "The workspace folder could not be restored: shared storage is not available."
            }
        }
        if (!sameDevice && boundSkipped > 0) {
            problems += "Tailscale sign-in belongs to another device, so it was not restored. Tap Start node to sign in again."
        }

        val message = "Restored the database, ${prefsCountText(prefs)} and $files file(s). " +
            "The previous database was kept as ${HermesDatabase.DATABASE_NAME}${FullBackupFormat.KEPT_DATABASE_SUFFIX}."
        return RestoreResult(problems.isEmpty(), System.currentTimeMillis(), message, problems)
    }

    private fun prefsCountText(n: Int) = "$n preference file(s)"

    private fun swapDatabase(context: Context, staged: File) {
        val target = context.getDatabasePath(HermesDatabase.DATABASE_NAME)
        target.parentFile?.mkdirs()
        val incoming = File(target.parentFile, target.name + ".restore-tmp")
        staged.copyTo(incoming, overwrite = true)
        if (!DatabaseFiles.isIntact(incoming)) {
            incoming.delete()
            throw IOException("the database in the backup did not pass its integrity check")
        }
        val kept = File(target.parentFile, target.name + FullBackupFormat.KEPT_DATABASE_SUFFIX)
        if (target.exists()) {
            Files.move(target.toPath(), kept.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // A leftover log or shared-memory file from the old database would be replayed onto the new.
        File(target.path + "-wal").delete()
        File(target.path + "-shm").delete()
        File(target.path + "-journal").delete()
        try {
            Files.move(incoming.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            // Put the old one back rather than leave the app without a database.
            if (kept.exists()) Files.move(kept.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            throw t
        }
    }

    private fun copyTree(from: File, to: File, problems: MutableList<String>, include: (relative: String) -> Boolean): Int {
        if (!from.isDirectory) return 0
        var count = 0
        from.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(from).invariantSeparatorsPath
            if (!include(relative)) return@forEach
            val destination = File(to, relative)
            try {
                destination.parentFile?.mkdirs()
                file.copyTo(destination, overwrite = true)
                count++
            } catch (e: IOException) {
                problems += "$relative: ${e.message}"
            }
        }
        return count
    }
}
