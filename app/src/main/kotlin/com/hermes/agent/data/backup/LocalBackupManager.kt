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
            checkpointWal()

            // Preferred: "Hermes Agent/Backup" at the root of internal storage,
            // where the user expects to find it. Only reachable with All files
            // access — MediaStore refuses any RELATIVE_PATH that does not start
            // with a standard directory, so it cannot create a top-level folder.
            val directResult = exportViaInternalStorage(fileName)
            if (directResult.isSuccess) {
                return@withContext directResult
            }

            // Without that permission, MediaStore can still write under Downloads.
            val mediaStoreResult = exportViaMediaStore(fileName)
            if (mediaStoreResult.isSuccess) {
                return@withContext mediaStoreResult
            }

            // Fallback to app-specific storage if MediaStore fails
            exportViaAppSpecificStorage(fileName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to export local backup")
            Result.failure(e)
        }
    }

    private fun checkpointWal() {
        try {
            database.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { it.moveToFirst() }
        } catch (e: Exception) {
            // A failed checkpoint still leaves a restorable backup, because the
            // -wal and -shm files are zipped alongside the database.
            Timber.w(e, "WAL checkpoint before backup failed; continuing")
        }
    }

    /**
     * Writes to `<internal storage>/Hermes Agent/Backup`, the location the user
     * is told to look in. Requires All files access on Android 11+; callers
     * fall through to MediaStore when that is not granted.
     */
    private fun exportViaInternalStorage(fileName: String): Result<BackupLocation> {
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
                writeZipToStream(outputStream)
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

    private fun exportViaMediaStore(fileName: String): Result<BackupLocation> {
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
                writeZipToStream(outputStream)
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

    private fun exportViaAppSpecificStorage(fileName: String): Result<BackupLocation> {
        return try {
            val backupDir = File(context.getExternalFilesDir(null), "Backup").apply { mkdirs() }
            val backupFile = File(backupDir, fileName)
            FileOutputStream(backupFile).use { outputStream ->
                writeZipToStream(outputStream)
            }
            Result.success(
                BackupLocation(Uri.fromFile(backupFile), backupFile.absolutePath),
            )
        } catch (e: Exception) {
            Timber.e(e, "App-specific backup storage failed")
            Result.failure(e)
        }
    }

    private fun writeZipToStream(outputStream: java.io.OutputStream) {
        ZipOutputStream(outputStream).use { zos ->
            // Database files
            val dbFile = context.getDatabasePath("hermes.db")
            val walFile = context.getDatabasePath("hermes.db-wal")
            val shmFile = context.getDatabasePath("hermes.db-shm")

            val filesToBackup = mutableListOf<File>()
            listOf(dbFile, walFile, shmFile).forEach { if (it.exists()) filesToBackup.add(it) }

            // DataStore directory files
            val dataStoreDir = File(context.filesDir, "datastore")
            if (dataStoreDir.exists() && dataStoreDir.isDirectory) {
                dataStoreDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".preferences_pb")) {
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

    suspend fun restoreFromZip(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            resolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val fileName = entry.name
                        val targetFile = when (fileName) {
                            "hermes.db", "hermes.db-wal", "hermes.db-shm" -> context.getDatabasePath(fileName)
                            "hermes_settings.preferences_pb" -> File(context.filesDir, "datastore/$fileName")
                            else -> null
                        }

                        if (targetFile != null) {
                            targetFile.parentFile?.mkdirs()
                            FileOutputStream(targetFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: return@withContext Result.failure(Exception("Failed to open input stream for zip"))

            // Restart app to load new data
            restartApp()
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore local backup")
            Result.failure(e)
        }
    }

    private fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 500, pendingIntent)
        Process.killProcess(Process.myPid())
    }

    private companion object {
        /** Top-level folder in internal storage, shared by every export path. */
        const val BACKUP_FOLDER_NAME = "Hermes Agent"
    }
}
