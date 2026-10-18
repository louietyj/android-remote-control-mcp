package com.danielealbano.androidremotecontrolmcp.services.shell

/** Outcome of one shell command. [exitCode] is -1 if the process could not be reaped. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val timedOut: Boolean,
    val uid: Int,
)

/** Shizuku is not installed, not running, or its shell service could not be reached. */
class ShellUnavailableException(
    message: String,
) : Exception(message)

/** Executes shell commands with elevated (adb shell) privileges. */
interface ShellExecutor {
    /**
     * Runs [command] via `sh -c`. Fails with [ShellUnavailableException] when the backend is
     * unreachable, or [SecurityException] when this app has not been granted access.
     */
    suspend fun exec(
        command: String,
        timeoutMs: Long,
    ): Result<ShellResult>

    /** Tears down the backend process, if any. Idempotent. */
    fun release()

    companion object {
        const val MAX_OUTPUT_BYTES = 64 * 1024
    }
}
