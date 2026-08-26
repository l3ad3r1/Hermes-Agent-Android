package com.hermes.agent.data.device

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.hermes.agent.BuildConfig
import com.hermes.agent.domain.device.PrivilegedShellBackend
import com.hermes.agent.service.IPrivilegedShellService
import com.hermes.agent.service.PrivilegedShellUserService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivilegedShellGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val retryGate: PrivilegedShellRetryGate,
) : PrivilegedShellBackend {

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val ADB_START_COMMAND = "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"
        private const val REQUEST_CODE_PERMISSION = 7391
    }

    private val mutex = Mutex()
    private var serviceInstance: IPrivilegedShellService? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedShellUserService::class.java.name)
    )
        .processNameSuffix("privileged_shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceInstance = IPrivilegedShellService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceInstance = null
        }
    }

    override suspend fun getStatus(): PrivilegedShellBackend.PrivilegedStatus = withContext(Dispatchers.IO) {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        if (!binderAlive) {
            val isInstalled = isShizukuInstalled()
            val status = if (isInstalled) {
                PrivilegedShellBackend.Status.DEAD
            } else {
                PrivilegedShellBackend.Status.NOT_INSTALLED
            }
            return@withContext PrivilegedShellBackend.PrivilegedStatus(status = status)
        }

        val hasPermission = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!hasPermission) {
            return@withContext PrivilegedShellBackend.PrivilegedStatus(
                status = PrivilegedShellBackend.Status.PERMISSION_REQUIRED,
                uid = runCatching { Shizuku.getUid() }.getOrDefault(-1),
                version = runCatching { Shizuku.getVersion() }.getOrDefault(-1),
            )
        }

        PrivilegedShellBackend.PrivilegedStatus(
            status = PrivilegedShellBackend.Status.READY,
            uid = runCatching { Shizuku.getUid() }.getOrDefault(2000),
            version = runCatching { Shizuku.getVersion() }.getOrDefault(-1),
        )
    }

    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return@withContext false
        }

        if (runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) {
            return@withContext true
        }

        val latch = CountDownLatch(1)
        var granted = false

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == REQUEST_CODE_PERMISSION) {
                    granted = (grantResult == PackageManager.PERMISSION_GRANTED)
                    Shizuku.removeRequestPermissionResultListener(this)
                    latch.countDown()
                }
            }
        }

        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
            withContext(Dispatchers.IO) {
                latch.await(15, TimeUnit.SECONDS)
            }
        } catch (t: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
        }

        granted
    }

    private suspend fun ensureServiceConnected(): IPrivilegedShellService? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (serviceInstance != null && serviceInstance?.asBinder()?.isBinderAlive == true) {
                return@withLock serviceInstance
            }

            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
                val deadline = System.currentTimeMillis() + 3000L
                while (System.currentTimeMillis() < deadline) {
                    if (serviceInstance != null && serviceInstance?.asBinder()?.isBinderAlive == true) {
                        return@withLock serviceInstance
                    }
                    delay(50)
                }
            } catch (t: Throwable) {
                // Ignore bind error and return current instance
            }
            serviceInstance
        }
    }

    override suspend fun execute(command: String, timeoutMs: Long): Result<PrivilegedShellBackend.ExecResult> =
        withContext(Dispatchers.IO) {
            if (!retryGate.canExecute()) {
                val reason = retryGate.status.value.reason
                return@withContext Result.failure(
                    IllegalStateException("Privileged shell execution halted due to previous unverified process unwind ($reason). Reset gate in Settings before retrying."),
                )
            }

            val status = getStatus()
            if (status.status != PrivilegedShellBackend.Status.READY) {
                return@withContext Result.failure(
                    IllegalStateException("Shizuku is not ready (status: ${status.status})"),
                )
            }

            retryGate.onExecutionStart()

            try {
                val svc = ensureServiceConnected()
                    ?: run {
                        retryGate.onExecutionFailure(
                            unverifiedUnwind = true,
                            reason = "Failed to bind to Shizuku privileged User Service",
                        )
                        return@withContext Result.failure(
                            IllegalStateException("Failed to bind to Shizuku privileged User Service"),
                        )
                    }

                val rawResult = withTimeoutOrNull(timeoutMs) {
                    svc.execute(command)
                }

                if (rawResult == null || rawResult.startsWith("TIMEOUT\n")) {
                    retryGate.onExecutionFailure(
                        unverifiedUnwind = true,
                        reason = "Command timed out after ${timeoutMs / 1000}s",
                    )
                    return@withContext Result.failure(
                        TimeoutException("Privileged shell command timed out after ${timeoutMs / 1000}s"),
                    )
                }

                val firstNewline = rawResult.indexOf('\n')
                val exitCodeStr = if (firstNewline != -1) rawResult.substring(0, firstNewline) else rawResult
                val output = if (firstNewline != -1 && firstNewline + 1 < rawResult.length) rawResult.substring(firstNewline + 1) else ""
                val exitCode = exitCodeStr.toIntOrNull() ?: 0

                retryGate.onExecutionSuccess()
                Result.success(PrivilegedShellBackend.ExecResult(exitCode = exitCode, output = output))
            } catch (t: Throwable) {
                retryGate.onExecutionFailure(
                    unverifiedUnwind = true,
                    reason = "Process execution failed: ${t.message ?: t.javaClass.simpleName}",
                )
                Result.failure(t)
            }
        }

    private fun isShizukuInstalled(): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }
}
