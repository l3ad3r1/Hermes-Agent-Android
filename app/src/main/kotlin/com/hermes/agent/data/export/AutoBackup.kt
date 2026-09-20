package com.hermes.agent.data.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hermes.agent.data.security.KeystoreManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Naming and pruning of the automatic backup files, kept apart from Android so it can be tested. */
object AutoBackupPolicy {
    const val PREFIX = "hermes-auto-backup-"
    const val SUFFIX = ".hbk"
    const val DEFAULT_KEEP = 5

    fun fileName(nowMillis: Long): String =
        PREFIX + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(nowMillis)) + SUFFIX

    /**
     * The files to delete so that only the newest [keep] automatic backups remain. Only files this
     * feature named are considered, so nothing else in the folder is ever touched. The names sort
     * by date, so the order of the names is the order of the backups.
     */
    fun toDelete(names: Collection<String>, keep: Int): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
            .sortedDescending()
            .drop(keep.coerceAtLeast(1))
}

/**
 * What the user chose for automatic backups. The password is stored sealed with a key that lives
 * in the Android Keystore, so the unattended worker can use it but it is not readable from a copy
 * of the app's files. (Anyone who can unlock this phone and open Hermes can still turn the
 * feature off; this protects the file at rest, not against the device owner.)
 */
@Singleton
class AutoBackupStore @Inject constructor(
    @ApplicationContext context: Context,
    private val keystore: KeystoreManager,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** The folder the user picked, as a tree uri string, or null. */
    var folder: String?
        get() = prefs.getString("folder", null)
        set(v) = prefs.edit().putString("folder", v).apply()

    var everyHours: Int
        get() = prefs.getInt("every_hours", 24)
        set(v) = prefs.edit().putInt("every_hours", v).apply()

    var keep: Int
        get() = prefs.getInt("keep", AutoBackupPolicy.DEFAULT_KEEP)
        set(v) = prefs.edit().putInt("keep", v).apply()

    var lastAt: Long
        get() = prefs.getLong("last_at", 0L)
        set(v) = prefs.edit().putLong("last_at", v).apply()

    /** A short human line about the last run, success or failure. */
    var lastResult: String
        get() = prefs.getString("last_result", "") ?: ""
        set(v) = prefs.edit().putString("last_result", v).apply()

    val hasPassword: Boolean get() = prefs.contains("password")

    fun setPassword(password: String) {
        val sealed = keystore.encrypt(ALIAS, password.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("password", Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun password(): String? = prefs.getString("password", null)?.let {
        runCatching { String(keystore.decrypt(ALIAS, Base64.decode(it, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrNull()
    }

    private companion object {
        const val PREFS = "auto_backup"
        const val ALIAS = "auto_backup_password"
    }
}

object AutoBackupScheduler {
    private const val WORK = "auto_backup"
    private const val WORK_NOW = "auto_backup_now"

    /** Turns the periodic job on or off to match the settings. */
    fun apply(context: Context, store: AutoBackupStore, replace: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!store.enabled || store.folder == null || !store.hasPassword) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(store.everyHours.toLong(), TimeUnit.HOURS)
            .setInitialDelay(store.everyHours.toLong(), TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutoBackupWorker>().build(),
        )
    }
}

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val manager: FullBackupManager,
    private val store: AutoBackupStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val folder = store.folder?.let(Uri::parse)
        val password = store.password()
        if (folder == null || password == null) {
            return finish("Not set up: choose a folder and a password.", Result.failure())
        }
        val resolver = appContext.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        var created: Uri? = null
        return try {
            val target = DocumentsContract.createDocument(
                resolver, root, "application/octet-stream", AutoBackupPolicy.fileName(System.currentTimeMillis()),
            ) ?: error("Could not create a file in the chosen folder.")
            created = target
            val summary = resolver.openOutputStream(target)?.use { manager.backup(it, password) }
                ?: error("Could not write to the chosen folder.")
            prune(folder, root)
            finish("Backed up: database ${summary.databaseBytes / 1024} KB, ${summary.files} files.", Result.success())
        } catch (e: Exception) {
            // A backup that failed half way must not sit there looking like a good one.
            created?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            // Retrying at once rarely helps (a folder that is gone stays gone); the next scheduled run tries again.
            finish("Failed: ${e.message ?: e.javaClass.simpleName}", Result.failure())
        }
    }

    private fun prune(tree: Uri, root: Uri) {
        val resolver = appContext.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(root))
        val byName = mutableMapOf<String, Uri>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                byName[c.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        AutoBackupPolicy.toDelete(byName.keys, store.keep).forEach { name ->
            runCatching { DocumentsContract.deleteDocument(resolver, byName.getValue(name)) }
        }
    }

    private fun finish(message: String, result: Result): Result {
        store.lastAt = System.currentTimeMillis()
        store.lastResult = message
        return result
    }
}
