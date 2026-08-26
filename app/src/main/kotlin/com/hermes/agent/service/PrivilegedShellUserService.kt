package com.hermes.agent.service

import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku User Service running in a separate elevated process (UID 2000 ADB shell).
 */
class PrivilegedShellUserService : IPrivilegedShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun getUid(): Int {
        return Process.myUid()
    }

    override fun execute(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val rawBytes = ByteArrayOutputStream()
            val inputStream = process.inputStream

            val deadline = System.currentTimeMillis() + 15000L
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val available = inputStream.available()
                if (available > 0) {
                    val n = inputStream.read(buf, 0, minOf(available, buf.size))
                    if (n > 0) rawBytes.write(buf, 0, n)
                }
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) break
            }

            val exited = process.waitFor(0, TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                return "TIMEOUT\nCommand timed out after 15s"
            }

            rawBytes.write(inputStream.readBytes())
            val exitCode = process.exitValue()
            val output = rawBytes.toByteArray()
                .toString(Charsets.UTF_8)
                .filter { it.code != 0 }
                .trim()

            "$exitCode\n$output"
        } catch (t: Throwable) {
            "-1\nExecution error: ${t.message ?: t.javaClass.simpleName}"
        }
    }
}
