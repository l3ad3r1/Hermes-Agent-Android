package com.hermes.agent.data.hotfix

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

/** A verified patch handed to Tinker; [installed] once Tinker's :patch process finished it. */
@Serializable
data class StagedPatch(
    val patchVersion: Int,
    val baseTinkerId: String,
    val sha256: String,
    /** MD5 of the patch file: Tinker names a patch version by it. */
    val md5: String,
    val sizeBytes: Long,
    val notes: String,
    val path: String,
    val minRestartPrompt: Boolean = true,
    val stagedAtMillis: Long,
    val installed: Boolean = false,
)

/** The patch Tinker last reported as loaded at start-up. */
@Serializable
data class AppliedPatch(
    val patchVersion: Int,
    val baseTinkerId: String,
    val md5: String,
    val notes: String,
    val loadedAtMillis: Long,
    /** From the staged record, so Tinker's oat-repair of this patch can be re-verified; "" if unknown. */
    val sha256: String = "",
    val sizeBytes: Long = 0,
)

@Serializable
data class HotfixEvent(
    val kind: Kind,
    val message: String,
    val atMillis: Long,
    val patchVersion: Int? = null,
) {
    @Serializable
    enum class Kind { STAGED, READY, INSTALL_FAILED, REJECTED, LOADED, LOAD_FAILED, ROLLED_BACK, REMOVED }
}

@Serializable
data class HotfixState(
    val staged: StagedPatch? = null,
    val applied: AppliedPatch? = null,
    val lastEvent: HotfixEvent? = null,
    val blockedPatchVersions: List<Int> = emptyList(),
    /** The patch set changed (applied, removed, rolled back) and the running code is not it yet. */
    val restartPending: Boolean = false,
)

/**
 * Patch state shared by the main process and Tinker's `:patch` process, so it is a small JSON
 * file (SharedPreferences are not multi-process safe) updated under a file lock and replaced
 * atomically. Kept in app-private storage.
 */
class HotfixStateStore(private val dir: File) {
    private val file get() = File(dir, "state.json")
    private val lockFile get() = File(dir, "state.lock")

    fun read(): HotfixState = runCatching {
        file.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(HotfixState.serializer(), it) }
    }.getOrNull() ?: HotfixState()

    fun update(transform: (HotfixState) -> HotfixState): HotfixState {
        dir.mkdirs()
        return RandomAccessFile(lockFile, "rw").use { raf ->
            val lock = raf.channel.lock()
            try {
                val next = transform(read())
                val tmp = File(dir, "state.json.tmp")
                tmp.writeText(json.encodeToString(HotfixState.serializer(), next))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
                next
            } finally {
                lock.release()
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** Small counter file for the crash guard: written synchronously on the crash path. */
class CrashCounterFile(private val file: File) {
    fun read(): Int = runCatching { file.readText().trim().toInt() }.getOrDefault(0)
    fun write(count: Int) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(count.toString())
        }
    }
}

/**
 * At a main-process start with no Tinker patch service running, a staged patch that was never
 * reported as prepared can no longer finish: drop it so it can be offered and applied again.
 */
fun abandonInterruptedPreparation(state: HotfixState, nowMillis: Long): HotfixState {
    val staged = state.staged ?: return state
    if (staged.installed) return state
    return state.copy(
        staged = null,
        lastEvent = HotfixEvent(
            HotfixEvent.Kind.INSTALL_FAILED,
            "Preparing fix #${staged.patchVersion} was interrupted; nothing was changed. Check for updates to try again.",
            nowMillis,
            staged.patchVersion,
        ),
    )
}
