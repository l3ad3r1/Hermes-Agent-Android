package com.hermes.agent.data.terminal

import com.hermes.agent.domain.terminal.RemoteTerminalBackend
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH implementation of [RemoteTerminalBackend] using JSch (mwiede fork,
 * pure-Java — no native libs, works on Android).
 *
 * Password auth against the configured host. Host-key checking is
 * disabled (`StrictHostKeyChecking=no`) because a phone app has no
 * known_hosts provisioning story; this trades MITM resistance for
 * usability and is called out as PARTIAL in the security audit.
 *
 * The exec channel merges stderr into the output stream so the LLM sees
 * the same combined transcript the local shell tool produces.
 */
@Singleton
class SshTerminalBackend @Inject constructor() : RemoteTerminalBackend {

    override suspend fun execute(
        config: RemoteTerminalBackend.Config,
        command: String,
        timeoutMs: Long,
    ): Result<RemoteTerminalBackend.ExecResult> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalStateException("remote shell is not configured (host/user missing)"))
        }
        var session: Session? = null
        var channel: ChannelExec? = null
        runCatching {
            session = JSch().getSession(config.username, config.host, config.port).apply {
                setPassword(config.password)
                setConfig("StrictHostKeyChecking", "no")
                timeout = CONNECT_TIMEOUT_MS
                connect(CONNECT_TIMEOUT_MS)
            }

            channel = (session!!.openChannel("exec") as ChannelExec).apply {
                setCommand(command)
                inputStream = null
                setErrStream(null) // merged below
            }
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            channel!!.outputStream = null
            val stdout = channel!!.inputStream
            channel!!.setErrStream(err, true)
            channel!!.connect(CONNECT_TIMEOUT_MS)

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                while (stdout.available() > 0) {
                    val n = stdout.read(buf, 0, minOf(stdout.available(), buf.size))
                    if (n > 0) out.write(buf, 0, n)
                }
                if (channel!!.isClosed) break
                Thread.sleep(50)
            }
            // Drain what's left after close/timeout.
            while (stdout.available() > 0) {
                val n = stdout.read(buf, 0, minOf(stdout.available(), buf.size))
                if (n > 0) out.write(buf, 0, n)
            }

            if (!channel!!.isClosed) {
                throw IllegalStateException("remote command timed out after ${timeoutMs / 1000}s")
            }

            val combined = buildString {
                append(out.toByteArray().toString(Charsets.UTF_8))
                val e = err.toByteArray().toString(Charsets.UTF_8)
                if (e.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(e)
                }
            }.trim()

            RemoteTerminalBackend.ExecResult(
                exitCode = channel!!.exitStatus,
                output = combined,
            )
        }.onFailure {
            Timber.tag("SshBackend").w(it, "ssh exec failed against %s:%d", config.host, config.port)
        }.also {
            runCatching { channel?.disconnect() }
            runCatching { session?.disconnect() }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
