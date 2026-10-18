package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ShellExecutor] backed by a Shizuku UserService ([ShellUserService]) running as the shell uid.
 * The service is bound lazily on first use and rebound transparently if its process dies.
 */
@Singleton
class ShizukuShellExecutor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ShellExecutor {
        private val bindMutex = Mutex()

        @Volatile private var service: IShellService? = null

        @Volatile private var pendingBind: CompletableDeferred<IShellService>? = null

        @Volatile private var bound = false

        private val userServiceArgs by lazy {
            Shizuku
                .UserServiceArgs(ComponentName(context.packageName, ShellUserService::class.java.name))
                .daemon(false)
                .processNameSuffix(PROCESS_SUFFIX)
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
        }

        private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    if (binder == null || !binder.pingBinder()) {
                        pendingBind?.completeExceptionally(
                            ShellUnavailableException("Shizuku returned an invalid shell service binder"),
                        )
                        return
                    }
                    val connected = IShellService.Stub.asInterface(binder)
                    service = connected
                    pendingBind?.complete(connected)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(TAG, "Shizuku shell service disconnected")
                    service = null
                }
            }

        override suspend fun exec(
            command: String,
            timeoutMs: Long,
        ): Result<ShellResult> =
            withContext(Dispatchers.IO) {
                try {
                    val bundle = obtainService().exec(command, timeoutMs, ShellExecutor.MAX_OUTPUT_BYTES)
                    Result.success(bundle.toShellResult())
                } catch (e: RemoteException) {
                    service = null
                    Result.failure(ShellUnavailableException("Shizuku shell service failed: ${e.message}"))
                } catch (e: ShellUnavailableException) {
                    Result.failure(e)
                } catch (e: SecurityException) {
                    Result.failure(e)
                } catch (e: IllegalStateException) {
                    Result.failure(e)
                }
            }

        private suspend fun obtainService(): IShellService =
            bindMutex.withLock {
                service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
                service = null

                if (!ShizukuAccess.isBinderAlive()) {
                    throw ShellUnavailableException(
                        "Shizuku is not reachable. Start it in the Shizuku app; if it was restarted " +
                            "while this app was running, force-stop and reopen this app.",
                    )
                }
                if (!ShizukuAccess.isPermissionGranted()) {
                    throw SecurityException(
                        "Shizuku access not granted to this app. Grant it in the app's Permissions settings.",
                    )
                }

                val deferred = CompletableDeferred<IShellService>()
                pendingBind = deferred
                Shizuku.bindUserService(userServiceArgs, connection)
                bound = true
                withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
                    ?: throw ShellUnavailableException("Timed out starting the Shizuku shell service")
            }

        override fun release() {
            service = null
            if (!bound) return
            bound = false
            try {
                if (Shizuku.pingBinder()) {
                    Shizuku.unbindUserService(userServiceArgs, connection, true)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Failed to unbind Shizuku shell service", e)
            }
        }

        private fun Bundle.toShellResult() =
            ShellResult(
                exitCode = getInt(ShellResultKeys.EXIT_CODE),
                stdout = getString(ShellResultKeys.STDOUT).orEmpty(),
                stderr = getString(ShellResultKeys.STDERR).orEmpty(),
                stdoutTruncated = getBoolean(ShellResultKeys.STDOUT_TRUNCATED),
                stderrTruncated = getBoolean(ShellResultKeys.STDERR_TRUNCATED),
                timedOut = getBoolean(ShellResultKeys.TIMED_OUT),
                uid = getInt(ShellResultKeys.UID),
            )

        private companion object {
            const val TAG = "MCP:ShizukuShellExecutor"
            const val PROCESS_SUFFIX = "shell"
            const val BIND_TIMEOUT_MS = 10_000L
        }
    }
