package com.hermes.agent.data.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import com.hermes.agent.data.local.HermesDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a backup actually landed.
 *
 * The export falls back from MediaStore to app-private storage, and the two
 * end up in very different places. Callers used to hard-code the MediaStore
 * path in their success message, so a fallback export told the user to look
 * in Download/Hermes Agent/Backup for a file that was never written there.
 */
data class BackupLocation(
    val uri: Uri,
    /** Human-readable path to show the user. */
    val displayPath: String,
)

@Singleton
class LocalBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: HermesDatabase,
) {

    suspend fun exportToZip(): Result<BackupLocation> = withContext(Dispatchers.IO) {
        try {
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "hermes_backup_$dateStr.zip"

            // Flush the write-ahead log into hermes.db first. Room keeps recent
            // writes in the -wal, so zipping the three files mid-write could
            // capture a torn set; checkpointing makes the copy self-consistent.
            val checkpointed = checkpointWal()

            // Preferred: "Hermes Agent/Backup" at the root of internal storage,
            // where the user expects to find it. Only reachable with All files
            // access — MediaStore refuses any RELATIVE_PATH that does not start
            // with a standard directory, so it cannot create a top-level folder.
            val directResult = exportViaInternalStorage(fileName, checkpointed)
            if (directResult.isSuccess) {
                return@withContext directResult
            }

            // Without that permission, MediaStore can still write under Downloads.
            val mediaStoreResult = exportViaMediaStore(fileName, checkpointed)
            if (mediaStoreResult.isSuccess) {
                return@withContext mediaStoreResult
            }

            // Fallback to app-specific storage if MediaStore fails
            exportViaAppSpecificStorage(fileName, checkpointed)
        } catch (e: Exception) {
            Timber.e(e, "Failed to export local backup")
            Result.failure(e)
        }
    }

    /**
     * Folds the write-ahead log back into hermes.db.
     *
     * Returns true when the database file alone is a complete snapshot. On
     * false the caller must zip the -wal and -shm alongside it, because recent
     * writes are still only in the log.
     */
    private fun checkpointWal(): Boolean {
        return try {
            database.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { it.moveToFirst() }
            true
        } catch (e: Exception) {
            Timber.w(e, "WAL checkpoint before backup failed; continuing")
            false
        }
    }

    /**
     * Writes to `<internal storage>/Hermes Agent/Backup`, the location the user
     * is told to look in. Requires All files access on Android 11+; callers
     * fall through to MediaStore when that is not granted.
     */
    private fun exportViaInternalStorage(fileName: String, checkpointed: Boolean): Result<BackupLocation> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return Result.failure(IllegalStateException("All files access not granted"))
            }

            val backupDir = File(Environment.getExternalStorageDirectory(), "$BACKUP_FOLDER_NAME/Backup")
            if (!backupDir.exists() && !backupDir.mkdirs()) {
                return Result.failure(IllegalStateException("Could not create ${backupDir.absolutePath}"))
            }

            val backupFile = File(backupDir, fileName)
            FileOutputStream(backupFile).use { outputStream ->
                writeZipToStream(outputStream, checkpointed)
            }

            // Written with raw file I/O, so MediaStore does not know about it
            // yet and the file would not appear in file managers until the next
            // media scan.
            MediaScannerConnection.scanFile(context, arrayOf(backupFile.absolutePath), arrayOf("application/zip"), null)

            Result.success(
                BackupLocation(Uri.fromFile(backupFile), "$BACKUP_FOLDER_NAME/Backup/$fileName"),
            )
        } catch (e: Exception) {
            Timber.w(e, "Internal-storage backup export failed, falling back to MediaStore")
            Result.failure(e)
        }
    }

    private fun exportViaMediaStore(fileName: String, checkpointed: Boolean): Result<BackupLocation> {
        return try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Hermes Agent/Backup")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val uri = resolver.insert(collection, contentValues)
                ?: return Result.failure(IllegalStateException("Failed to create MediaStore entry"))

            resolver.openOutputStream(uri)?.use { outputStream ->
                writeZipToStream(outputStream, checkpointed)
            } ?: return Result.failure(IllegalStateException("Failed to open output stream for MediaStore Uri"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            Result.success(
                BackupLocation(uri, "Download/Hermes Agent/Backup/$fileName"),
            )
        } catch (e: Exception) {
            Timber.w(e, "MediaStore backup export failed, falling back to app storage")
            Result.failure(e)
        }
    }

    private fun exportViaAppSpecificStorage(fileName: String, checkpointed: Boolean): Result<BackupLocation> {
        return try {
            val backupDir = File(context.getExternalFilesDir(null), "Backup").apply { mkdirs() }
            val backupFile = File(backupDir, fileName)
            FileOutputStream(backupFile).use { outputStream ->
                writeZipToStream(outputStream, checkpointed)
            }
            Result.success(
                BackupLocation(Uri.fromFile(backupFile), backupFile.absolutePath),
            )
        } catch (e: Exception) {
            Timber.e(e, "App-specific backup storage failed")
            Result.failure(e)
        }
    }

    private fun writeZipToStream(outputStream: java.io.OutputStream, checkpointed: Boolean) {
        ZipOutputStream(outputStream).use { zos ->
            // Database files
            val dbFile = context.getDatabasePath(DB_NAME)
            val walFile = context.getDatabasePath(WAL_NAME)
            val shmFile = context.getDatabasePath(SHM_NAME)

            val filesToBackup = mutableListOf<File>()
            // After a successful checkpoint the log is empty and hermes.db is a
            // complete snapshot. Shipping the -wal/-shm anyway is worse than
            // useless: the -shm is a rebuildable index over the log, and a
            // restored pair that disagrees with the database is what SQLite
            // treats as corruption. Only carry them when the checkpoint failed.
            filesToBackup += dbFile.takeIf { it.exists() } ?: error("No database to back up.")
            if (!checkpointed) {
                listOf(walFile, shmFile).forEach { if (it.exists()) filesToBackup.add(it) }
            }

            // DataStore directory files
            val dataStoreDir = File(context.filesDir, "datastore")
            if (dataStoreDir.exists() && dataStoreDir.isDirectory) {
                dataStoreDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(PREFERENCES_SUFFIX)) {
                        filesToBackup.add(file)
                    }
                }
            }

            for (file in filesToBackup) {
                zos.putNextEntry(ZipEntry(file.name))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }

    /**
     * Replaces the live database and preferences with the contents of a backup.
     *
     * The archive is unpacked to a scratch directory and checked before anything
     * on disk is touched. The previous version of every file it replaces is kept
     * until the swap has finished, so a failure part-way through leaves the
     * install exactly as it was rather than half-restored.
     *
     * On success the caller must call [restartApp]: the process is holding a
     * database that no longer matches its files.
     */
    suspend fun restoreFromZip(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
        try {
            if (!staging.mkdirs()) {
                return@withContext Result.failure(
                    IllegalStateException("Could not create a temporary folder for the restore."),
                )
            }

            val extracted = extractBackup(uri, staging).getOrElse { return@withContext Result.failure(it) }

            // Refuse anything that is not a Hermes backup instead of restarting
            // into an unchanged app. The old code wrote whatever entries it
            // recognised — none, for an unrelated zip — and still reported
            // success, which is what "restore did nothing" looked like.
            val restoredDb = extracted[DB_NAME]
                ?: return@withContext Result.failure(
                    IllegalArgumentException(
                        "That file is not a Hermes backup — it contains no $DB_NAME.",
                    ),
                )
            if (!looksLikeSqliteDatabase(restoredDb)) {
                return@withContext Result.failure(
                    IllegalArgumentException("The backup's database is damaged and cannot be restored."),
                )
            }

            // Room is still holding the old files open. Writing underneath a live
            // connection is what corrupted a restore: the open database would
            // flush its own cached pages back over the ones just written.
            runCatching { database.close() }
                .onFailure { Timber.w(it, "Could not close the database before restoring") }

            installRestoredFiles(extracted).getOrElse { return@withContext Result.failure(it) }
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore local backup")
            Result.failure(e)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Unpacks the recognised entries of [uri] into [staging], keyed by file name. */
    private fun extractBackup(uri: Uri, staging: File): Result<Map<String, File>> {
        val extracted = mutableMapOf<String, File>()
        val stream = context.contentResolver.openInputStream(uri)
            ?: return Result.failure(IllegalStateException("Could not open the backup file."))
        stream.use { inputStream ->
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    // Take the base name only: an entry called "../../foo" would
                    // otherwise be written outside the staging directory.
                    val name = File(entry.name).name
                    if (!entry.isDirectory && isRestorableEntry(name)) {
                        val target = File(staging, name)
                        FileOutputStream(target).use { fos -> zis.copyTo(fos) }
                        extracted[name] = target
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return Result.success(extracted)
    }

    private fun isRestorableEntry(name: String): Boolean =
        name == DB_NAME || name == WAL_NAME || name == SHM_NAME || name.endsWith(PREFERENCES_SUFFIX)

    /** SQLite stamps every database file with this 16-byte header. */
    private fun looksLikeSqliteDatabase(file: File): Boolean = try {
        val expected = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val actual = ByteArray(expected.size)
        FileInputStream(file).use { it.read(actual) }
        actual.contentEquals(expected)
    } catch (e: Exception) {
        Timber.w(e, "Could not read the backup database header")
        false
    }

    /** Swaps [extracted] into place, rolling back completely if any copy fails. */
    private fun installRestoredFiles(extracted: Map<String, File>): Result<Unit> {
        val dataStoreDir = File(context.filesDir, "datastore")
        val targets = extracted.mapValues { (name, _) ->
            when (name) {
                DB_NAME, WAL_NAME, SHM_NAME -> context.getDatabasePath(name)
                else -> File(dataStoreDir, name)
            }
        }

        // A log the backup does not carry must not survive the swap. SQLite
        // replays whatever -wal it finds beside the database, so leaving the
        // current one there would fold this install's writes into the restored
        // data — the "restored, but my old chats are still here" case.
        val staleLogs = listOf(WAL_NAME, SHM_NAME)
            .filter { it !in extracted }
            .map { context.getDatabasePath(it) }
            .filter { it.exists() }

        val rollback = mutableMapOf<File, File>()
        return try {
            for (file in targets.values + staleLogs) {
                if (!file.exists()) continue
                val saved = File(file.parentFile, "${file.name}.pre-restore")
                saved.delete()
                file.copyTo(saved, overwrite = true)
                rollback[file] = saved
            }

            for ((name, source) in extracted) {
                val target = targets.getValue(name)
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
            staleLogs.forEach { it.delete() }

            rollback.values.forEach { it.delete() }
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Restore failed part-way; rolling back")
            rollback.forEach { (original, saved) ->
                runCatching { saved.copyTo(original, overwrite = true) }
                saved.delete()
            }
            Result.failure(
                IllegalStateException(
                    "The restore could not be completed and your existing data was kept. ${e.message.orEmpty()}".trim(),
                ),
            )
        }
    }

    /**
     * Relaunches Hermes so it reopens the restored database.
     *
     * The activity is started directly rather than through an alarm. A
     * non-wakeup alarm scheduled by a process that is about to die is not
     * guaranteed to fire promptly, and on recent Android versions a background
     * activity start from an alarm can be blocked outright — which left the app
     * simply dead after a restore.
     */
    fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent != null) {
            runCatching { context.startActivity(intent) }
                .onFailure { Timber.w(it, "Could not relaunch after restore; scheduling instead") }
                .onFailure { scheduleRelaunch(intent) }
        }
        Process.killProcess(Process.myPid())
    }

    private fun scheduleRelaunch(intent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 500, pendingIntent)
    }

    private companion object {
        /** Top-level folder in internal storage, shared by every export path. */
        const val BACKUP_FOLDER_NAME = "Hermes Agent"
        const val DB_NAME = "hermes.db"
        const val WAL_NAME = "hermes.db-wal"
        const val SHM_NAME = "hermes.db-shm"

        /**
         * Every DataStore file is restored, not just hermes_settings. The export
         * has always collected all of them, so hermes_learning_state was written
         * into the archive and then silently dropped on the way back in.
         */
        const val PREFERENCES_SUFFIX = ".preferences_pb"
    }
}
