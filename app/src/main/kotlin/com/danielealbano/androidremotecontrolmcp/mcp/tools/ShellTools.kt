@file:Suppress("MatchingDeclarationName")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellExecutor
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// shell
// ─────────────────────────────────────────────────────────────────────────────

class ShellHandler
    @Inject
    constructor(
        private val shellExecutor: ShellExecutor,
        private val privacyToolGate: PrivacyToolGate,
        private val placeholderSubstitutor: PlaceholderSubstitutor,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val command = requireCommand(arguments)
            val timeoutMs = McpToolUtils.optionalLong(arguments, "timeout_ms", DEFAULT_TIMEOUT_MS)
            McpToolUtils.validatePositiveRange(timeoutMs, "timeout_ms", MAX_TIMEOUT_MS)

            Log.d(TAG, "Executing shell command (timeout ${timeoutMs}ms)")
            val shell =
                shellExecutor
                    .exec(placeholderSubstitutor.substitute(command), timeoutMs)
                    .getOrElse { throw toToolException(it) }

            val jsonResult =
                buildJsonObject {
                    put("exit_code", shell.exitCode)
                    put("stdout", privacyToolGate.text(shell.stdout, "stdout"))
                    put("stderr", privacyToolGate.text(shell.stderr, "stderr"))
                    put("timed_out", shell.timedOut)
                    put("stdout_truncated", shell.stdoutTruncated)
                    put("stderr_truncated", shell.stderrTruncated)
                    put("uid", shell.uid)
                }

            return McpToolUtils.textResult(jsonResult.toString())
        }

        private fun requireCommand(arguments: JsonObject?): String {
            val command = McpToolUtils.requireString(arguments, "command")
            if (command.isBlank()) {
                throw McpToolException.InvalidParams("Parameter 'command' must not be blank")
            }
            return command
        }

        private fun toToolException(exception: Throwable): McpToolException {
            val message = exception.message ?: "Unknown error"
            return if (exception is SecurityException) {
                McpToolException.PermissionDenied(message)
            } else {
                McpToolException.ActionFailed(message)
            }
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Runs a command on the device with adb shell privileges (via Shizuku), equivalent " +
                        "to `adb shell <command>`. The command is run with `sh -c`, so pipes, redirects " +
                        "and `&&` work. Returns exit_code, stdout, stderr (each capped at " +
                        "${ShellExecutor.MAX_OUTPUT_BYTES / BYTES_PER_KIB} KiB; *_truncated flags " +
                        "report truncation), timed_out, and uid (2000 = shell, 0 = root). No stdin and " +
                        "no streaming: use non-blocking forms (e.g. `logcat -d`, not `logcat`); a command " +
                        "still running at timeout_ms is killed. Host-side adb verbs (install, push, pull) " +
                        "are not available; use on-device equivalents (`pm install /sdcard/x.apk`, `cp`, " +
                        "`cat`). `curl` is on PATH (bundled, using Android's DNS and CA store), so " +
                        "`curl -fsSLo /data/local/tmp/x.apk <url> && pm install -r /data/local/tmp/x.apk` " +
                        "works. Requires Shizuku to be running and to have granted this app access.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("command") {
                                    put("type", "string")
                                    put("description", "Shell command line, passed to `sh -c`.")
                                }
                                putJsonObject("timeout_ms") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Maximum run time in milliseconds before the command is killed " +
                                            "(default $DEFAULT_TIMEOUT_MS, max $MAX_TIMEOUT_MS).",
                                    )
                                }
                            },
                        required = listOf("command"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "shell"
            const val DEFAULT_TIMEOUT_MS = 30_000L
            const val MAX_TIMEOUT_MS = 120_000L
            private const val BYTES_PER_KIB = 1024
            private const val TAG = "MCP:ShellHandler"
        }
    }

fun registerShellTools(
    registrar: LoggedToolRegistrar,
    shellExecutor: ShellExecutor,
    privacyToolGate: PrivacyToolGate,
    placeholderSubstitutor: PlaceholderSubstitutor,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(ShellHandler.TOOL_NAME)) {
        ShellHandler(shellExecutor, privacyToolGate, placeholderSubstitutor).register(registrar, toolNamePrefix)
    }
}
