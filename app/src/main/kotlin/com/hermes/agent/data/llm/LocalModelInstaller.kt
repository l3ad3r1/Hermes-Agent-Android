package com.hermes.agent.data.llm

import java.io.File

internal object LocalModelInstaller {
    /** Promotes a verified staged model while retaining the old file until promotion succeeds. */
    fun moveIntoPlace(
        staging: File,
        destDir: File,
        fileName: String,
        expectedSize: Long,
    ): Boolean {
        return try {
            if (!staging.isFile || staging.length() != expectedSize) return false
            if ((!destDir.exists() && !destDir.mkdirs()) || !destDir.isDirectory) return false
            val dest = File(destDir, fileName)
            val incoming = File(destDir, ".$fileName.incoming")
            val previous = File(destDir, ".$fileName.previous")
            if (incoming.exists() && !incoming.delete()) return false
            if (previous.exists() && !previous.delete()) return false
            if (!staging.renameTo(incoming)) {
                staging.copyTo(incoming, overwrite = false)
                if (incoming.length() != expectedSize || !staging.delete()) {
                    incoming.delete()
                    return false
                }
            }
            if (incoming.length() != expectedSize) {
                incoming.delete()
                return false
            }
            if (dest.exists() && !dest.renameTo(previous)) return false
            val promoted = incoming.renameTo(dest) && dest.length() == expectedSize
            if (promoted) {
                previous.delete()
                true
            } else {
                dest.delete()
                previous.renameTo(dest)
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
