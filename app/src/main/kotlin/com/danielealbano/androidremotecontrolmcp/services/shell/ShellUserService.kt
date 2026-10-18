package com.danielealbano.androidremotecontrolmcp.services.shell

import android.os.Bundle
import android.system.Os
import java.io.IOException
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
class ShellUserService : IShellService.Stub() {
    override fun exec(
        command: String,
        timeoutMs: Long,
        maxOutputBytes: Int,
    ): Bundle {
        val process =
            try {
                ProcessBuilder("sh", "-c", command).start()
            } catch (e: IOException) {
                // Binder only marshals a few exception types; IllegalStateException is one of them.
                throw IllegalStateException("Failed to start sh: ${e.message}", e)
            }
        // No stdin support: give commands that read it an immediate EOF.
        process.outputStream.close()

        val stdout = CappedOutputCollector(maxOutputBytes)
        val stderr = CappedOutputCollector(maxOutputBytes)
        val readers =
            listOf(
                startReader("shell-stdout") { stdout.drain(process.inputStream) },
                startReader("shell-stderr") { stderr.drain(process.errorStream) },
            )

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
        }
        // A backgrounded grandchild can hold the pipes open indefinitely; don't wait for it.
        readers.forEach { it.join(READER_JOIN_MS) }

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

    private fun startReader(
        name: String,
        block: () -> Unit,
    ): Thread =
        Thread(block, name).apply {
            isDaemon = true
            start()
        }

    private companion object {
        const val KILL_GRACE_MS = 1000L
        const val READER_JOIN_MS = 2000L
    }
}
