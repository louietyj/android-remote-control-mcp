package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Context
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Bundle keys shared by [ShellUserService] and its client. */
object ShellResultKeys {
    const val EXIT_CODE = "exit_code"
    const val STDOUT = "stdout"
    const val STDERR = "stderr"
    const val STDOUT_TRUNCATED = "stdout_truncated"
    const val STDERR_TRUNCATED = "stderr_truncated"
    const val TIMED_OUT = "timed_out"
    const val UID = "uid"
}

/**
 * Shizuku UserService, instantiated by Shizuku in its own shell-uid (or root) process, so it
 * MUST NOT depend on Hilt or the app's Application.
 */
class ShellUserService(
    private val nativeLibDir: String?,
) : IShellService.Stub() {
    // Shizuku prefers the Context constructor; the no-arg one is its fallback, and runs without bundled tools.
    constructor(context: Context) : this(context.applicationInfo.nativeLibraryDir)

    constructor() : this(null)

    // Tools already warned about, so a build without them doesn't log on every command.
    private val unlinked = ConcurrentHashMap.newKeySet<String>()

    override fun exec(
        command: String,
        timeoutMs: Long,
        maxOutputBytes: Int,
    ): Bundle {
        val builder = ProcessBuilder("setsid", "-w", "sh", "-c", PGID_WRAPPER, command)
        if (nativeLibDir != null) {
            BundledTools.link(nativeLibDir).filter { unlinked.add(it) }.forEach {
                Log.w(TAG, "Bundled tool not available on PATH: $it")
            }
            builder.environment().compute("PATH") { _, current -> BundledTools.path(current) }
        }
        val process =
            try {
                builder.start()
            } catch (e: IOException) {
                // Binder only marshals a few exception types; IllegalStateException is one of them.
                throw IllegalStateException("Failed to start sh: ${e.message}", e)
            }
        // No stdin support: give commands that read it an immediate EOF.
        process.outputStream.close()
        val pgid = readPgid(process.errorStream)

        val stdout = CappedOutputCollector(maxOutputBytes)
        val stderr = CappedOutputCollector(maxOutputBytes)
        val readers =
            listOf(
                startReader("shell-stdout") { stdout.drain(process.inputStream) },
                startReader("shell-stderr") { stderr.drain(process.errorStream) },
            )

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            // Kill the whole group: sh defers signals while waiting on a foreground child, and
            // pipeline members / background jobs would otherwise outlive it.
            if (pgid != null) {
                try {
                    Os.kill(-pgid, OsConstants.SIGKILL)
                } catch (_: ErrnoException) {
                    // Group already gone.
                }
            }
            process.destroyForcibly()
            process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
        }
        // A backgrounded grandchild can hold the pipes open indefinitely; don't wait for it.
        val readerDeadline = System.currentTimeMillis() + READER_GRACE_MS
        readers.forEach { it.join((readerDeadline - System.currentTimeMillis()).coerceAtLeast(1)) }

        val out = stdout.snapshot()
        val err = stderr.snapshot()
        return Bundle().apply {
            putInt(ShellResultKeys.EXIT_CODE, if (process.isAlive) -1 else process.exitValue())
            putString(ShellResultKeys.STDOUT, out.text)
            putString(ShellResultKeys.STDERR, err.text)
            putBoolean(ShellResultKeys.STDOUT_TRUNCATED, out.truncated)
            putBoolean(ShellResultKeys.STDERR_TRUNCATED, err.truncated)
            putBoolean(ShellResultKeys.TIMED_OUT, !finished)
            putInt(ShellResultKeys.UID, Os.getuid())
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    /** Reads the wrapper's first stderr line: the pid of the session/group leader running the command. */
    private fun readPgid(stderr: InputStream): Int? {
        val line = StringBuilder()
        while (line.length < MAX_PID_LINE) {
            val b = stderr.read()
            if (b < 0 || b == '\n'.code) break
            line.append(b.toChar())
        }
        return line.toString().toIntOrNull()
    }

    private fun startReader(
        name: String,
        block: () -> Unit,
    ): Thread =
        Thread(block, name).apply {
            isDaemon = true
            start()
        }

    private companion object {
        const val TAG = "MCP:ShellUserService"

        // Under `setsid -w`, $$ is the new session's (and process group's) id. The command arrives as $0.
        const val PGID_WRAPPER = "echo \$\$ >&2; exec sh -c \"\$0\""
        const val MAX_PID_LINE = 16
        const val KILL_GRACE_MS = 1000L
        const val READER_GRACE_MS = 1000L
    }
}
