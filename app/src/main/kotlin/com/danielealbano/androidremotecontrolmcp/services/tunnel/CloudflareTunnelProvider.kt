package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.CloudflareTunnelMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import javax.inject.Inject

/**
 * Cloudflare tunnel provider.
 *
 * Runs the `cloudflared` binary as a child process. Two modes are supported:
 * - [CloudflareTunnelMode.FREE]: a temporary Quick Tunnel with a random
 *   `*.trycloudflare.com` URL (no account or configuration needed).
 * - [CloudflareTunnelMode.TOKEN]: a remotely-managed named tunnel run with a
 *   dashboard token. The tunnel goes Connected as soon as it registers to the
 *   Cloudflare edge — even before any public hostname is configured — and stays
 *   running. Public hostname(s) and their origin service are configured in the
 *   dashboard; cloudflared logs them as `Updated to new configuration` lines,
 *   which we parse to populate the endpoint list. Each endpoint's service is
 *   validated against the local MCP server, but validation is ADVISORY: an
 *   invalid route is flagged (`TunnelEndpoint.valid == false`) for the UI to show
 *   a warning — it never stops the tunnel.
 */
class CloudflareTunnelProvider
    @Inject
    constructor(
        private val binaryResolver: CloudflaredBinaryResolver,
    ) : TunnelProvider {
        private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        override val status: StateFlow<TunnelStatus> = _status.asStateFlow()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var process: Process? = null
        private var stderrReaderJob: Job? = null
        private var processMonitorJob: Job? = null

        // Kept from the initial start so the exit monitor can relaunch the same tunnel.
        private var launchArgs: List<String> = emptyList()
        private var lineHandler: (String) -> Unit = {}
        private var restartAttempt = 0

        override suspend fun start(
            localPort: Int,
            config: ServerConfig,
        ) {
            mutex.withLock {
                check(process == null) { "Tunnel is already running" }

                val binaryPath =
                    binaryResolver.resolve() ?: run {
                        _status.value = TunnelStatus.Error("cloudflared binary not found")
                        return
                    }

                when (config.cloudflareTunnelMode) {
                    CloudflareTunnelMode.FREE -> startFreeTunnel(binaryPath, localPort, config)
                    CloudflareTunnelMode.TOKEN -> startTokenTunnel(binaryPath, localPort, config)
                }
            }
        }

        private fun startFreeTunnel(
            binaryPath: String,
            localPort: Int,
            config: ServerConfig,
        ) {
            val args =
                mutableListOf(
                    binaryPath,
                    "tunnel",
                    "--url",
                    "http://localhost:$localPort",
                    "--output",
                    "json",
                )
            if (config.cloudflareTunnelExtraArgs.isNotBlank()) {
                args.addAll(parseCommandLineArguments(config.cloudflareTunnelExtraArgs))
            }
            launchInitial(args) { line ->
                val match = TUNNEL_URL_REGEX.find(line)
                if (match != null && _status.value is TunnelStatus.Connecting) {
                    val url = match.value
                    Log.i(TAG, "Cloudflare tunnel URL: $url")
                    _status.value =
                        TunnelStatus.Connected(
                            endpoints = listOf(TunnelEndpoint(url = url, valid = true)),
                            providerType = TunnelProviderType.CLOUDFLARE,
                        )
                }
            }
        }

        private fun startTokenTunnel(
            binaryPath: String,
            localPort: Int,
            config: ServerConfig,
        ) {
            if (config.cloudflareTunnelToken.isEmpty()) {
                _status.value = TunnelStatus.Error("Cloudflare tunnel token is required")
                return
            }

            // NOTE: exact argument order; `--output json` MUST come before `run`, and `--url`
            // MUST NOT be passed (it breaks the control stream of a remotely-managed tunnel).
            val args =
                mutableListOf(
                    binaryPath,
                    "tunnel",
                    "--output",
                    "json",
                    "run",
                    "--token",
                    config.cloudflareTunnelToken,
                )
            if (config.cloudflareTunnelExtraArgs.isNotBlank()) {
                args.addAll(parseCommandLineArguments(config.cloudflareTunnelExtraArgs))
            }
            launchInitial(args) { line -> handleTokenLine(line, localPort) }
        }

        /** A failure to launch here is terminal; only a process that started and later died is restarted. */
        private fun launchInitial(
            args: List<String>,
            onLine: (String) -> Unit,
        ) {
            launchArgs = args
            lineHandler = onLine
            restartAttempt = 0
            _status.value = TunnelStatus.Connecting
            try {
                launchProcess()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.e(TAG, "Failed to start cloudflared process", e)
                _status.value = TunnelStatus.Error("Failed to start cloudflared: ${e.message}")
                process = null
            }
        }

        /** Caller holds [mutex]. */
        private fun launchProcess() {
            val pb = ProcessBuilder(launchArgs)
            pb.redirectErrorStream(false)
            val proc = pb.start()
            process = proc

            launchStderrReader(proc, lineHandler)
            startProcessMonitor(proc)
        }

        /**
         * Handles one token-mode stderr line. The tunnel becomes Connected (with no endpoints) as
         * soon as it registers to the edge, so it stays up while the user configures a route; each
         * `Updated to new configuration` push then refreshes the endpoint list. Per-endpoint service
         * validation is ADVISORY — an invalid route is flagged, never terminated. Non-JSON lines and
         * other messages are ignored.
         */
        private fun handleTokenLine(
            line: String,
            localPort: Int,
        ) {
            when (logMessageOf(line)) {
                MSG_REGISTERED -> {
                    if (_status.value is TunnelStatus.Connecting) {
                        _status.value =
                            TunnelStatus.Connected(
                                endpoints = emptyList(),
                                providerType = TunnelProviderType.CLOUDFLARE,
                            )
                    }
                }

                MSG_UPDATED_CONFIG -> {
                    val payload = configPayloadOf(line) ?: return
                    val endpoints =
                        ingressRoutesOf(payload).map { route ->
                            TunnelEndpoint(
                                url = "https://${route.hostname}",
                                valid = isServiceValid(route.service, localPort),
                            )
                        }
                    _status.value =
                        TunnelStatus.Connected(
                            endpoints = endpoints,
                            providerType = TunnelProviderType.CLOUDFLARE,
                        )
                }
            }
        }

        override suspend fun stop() {
            mutex.withLock {
                // Cancel the monitor even when no process is alive: it may be waiting out a restart backoff.
                stderrReaderJob?.cancel()
                stderrReaderJob = null
                processMonitorJob?.cancel()
                processMonitorJob = null

                process?.let { proc ->
                    proc.destroy()
                    withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                        @Suppress("BlockingMethodInNonBlockingContext")
                        proc.waitFor()
                    } ?: proc.destroyForcibly()
                }

                process = null
                _status.value = TunnelStatus.Disconnected
            }
        }

        /**
         * Launches a coroutine that reads the process stderr line by line and forwards each line to
         * [onLine]. Shared by both modes; mode-specific handling lives in the caller's lambda. The
         * reader is closed on any exit path (`use`) to release the file descriptor.
         */
        private fun launchStderrReader(
            proc: Process,
            onLine: (String) -> Unit,
        ) {
            stderrReaderJob =
                scope.launch {
                    try {
                        BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                            while (isActive) {
                                @Suppress("BlockingMethodInNonBlockingContext")
                                val line = reader.readLine() ?: break
                                Log.d(TAG, "cloudflared: $line")
                                onLine(line)
                            }
                        }
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        if (isActive) {
                            Log.w(TAG, "Error reading cloudflared stderr", e)
                        }
                    }
                }
        }

        /** Restarts [proc] when it dies: Android's phantom process killer routinely SIGKILLs it (exit 137). */
        private fun startProcessMonitor(proc: Process) {
            val startedAtMs = System.currentTimeMillis()
            processMonitorJob =
                scope.launch {
                    // Give the process a moment to start before monitoring exit
                    delay(PROCESS_MONITOR_INITIAL_DELAY_MS)

                    @Suppress("BlockingMethodInNonBlockingContext")
                    val exitCode = proc.waitFor()

                    if (isActive && _status.value !is TunnelStatus.Disconnected) {
                        Log.w(TAG, "cloudflared process exited unexpectedly with code $exitCode")
                        mutex.withLock {
                            process = null
                        }
                        if (System.currentTimeMillis() - startedAtMs >= RESTART_BACKOFF_RESET_MS) {
                            restartAttempt = 0
                        }
                        restartWithBackoff("cloudflared process exited unexpectedly (code $exitCode)")
                    }
                }
        }

        /** Returns once a relaunch succeeds; the new process gets its own monitor job. */
        private suspend fun restartWithBackoff(initialReason: String) {
            var reason = initialReason
            while (true) {
                val delayMs = restartDelayMs(restartAttempt)
                restartAttempt++
                _status.value = TunnelStatus.Error("$reason; restarting in ${delayMs / MS_PER_SECOND}s")
                delay(delayMs)

                val failure =
                    mutex.withLock {
                        currentCoroutineContext().ensureActive()
                        _status.value = TunnelStatus.Connecting
                        runCatching { launchProcess() }.onFailure { process = null }.exceptionOrNull()
                    } ?: return

                Log.e(TAG, "Failed to restart cloudflared process", failure)
                reason = "Failed to restart cloudflared: ${failure.message}"
            }
        }

        /** A Cloudflare ingress rule that declares a public hostname. */
        internal data class IngressRoute(
            val hostname: String,
            val service: String,
        )

        companion object {
            private const val TAG = "MCP:CloudflareTunnel"
            internal val TUNNEL_URL_REGEX =
                Regex("https://[-a-zA-Z0-9]+\\.trycloudflare\\.com")
            internal const val SHUTDOWN_TIMEOUT_MS = 5_000L
            private const val PROCESS_MONITOR_INITIAL_DELAY_MS = 1_000L
            private const val RESTART_MIN_DELAY_MS = 1_000L
            private const val RESTART_MAX_DELAY_MS = 60_000L
            private const val RESTART_MAX_DOUBLINGS = 6

            /** A process that stayed up this long was healthy, so its restart starts back at the minimum delay. */
            private const val RESTART_BACKOFF_RESET_MS = 60_000L
            private const val MS_PER_SECOND = 1_000L
            internal const val MSG_REGISTERED = "Registered tunnel connection"
            internal const val MSG_UPDATED_CONFIG = "Updated to new configuration"
            internal val LOG_JSON = Json { ignoreUnknownKeys = true }

            /** 1s, 2s, 4s, … capped at 60s. */
            internal fun restartDelayMs(attempt: Int): Long =
                minOf(
                    RESTART_MIN_DELAY_MS shl attempt.coerceIn(0, RESTART_MAX_DOUBLINGS),
                    RESTART_MAX_DELAY_MS,
                )

            /** Returns the top-level `message` field of a cloudflared JSON log line, or null for non-JSON. */
            internal fun logMessageOf(line: String): String? =
                runCatching {
                    LOG_JSON
                        .parseToJsonElement(line)
                        .jsonObject["message"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull()

            /** Returns the escaped `config` payload of an `Updated to new configuration` line, or null. */
            internal fun configPayloadOf(line: String): String? =
                runCatching {
                    LOG_JSON
                        .parseToJsonElement(line)
                        .jsonObject["config"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull()

            /** Parses the nested config JSON into the ingress entries that declare a hostname. */
            internal fun ingressRoutesOf(configJson: String): List<IngressRoute> =
                runCatching {
                    LOG_JSON
                        .parseToJsonElement(configJson)
                        .jsonObject["ingress"]
                        ?.jsonArray
                        .orEmpty()
                        .mapNotNull { element ->
                            val obj = element.jsonObject
                            val host = obj["hostname"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            IngressRoute(host, obj["service"]?.jsonPrimitive?.contentOrNull ?: "")
                        }
                }.getOrDefault(emptyList())

            /**
             * True when the routed service points at our MCP server: `http://(localhost|127.0.0.1):<port>`.
             * Scheme and host are compared case-insensitively (RFC 3986); any service carrying userinfo,
             * a query, or a fragment is rejected (cloudflared never emits those for an origin service).
             */
            internal fun isServiceValid(
                service: String,
                expectedPort: Int,
            ): Boolean {
                val uri = runCatching { URI(service) }.getOrNull() ?: return false
                val host = uri.host?.lowercase()
                val path = uri.path
                val isLoopback = host == "localhost" || host == "127.0.0.1"
                val pathIsRoot = path.isNullOrEmpty() || path == "/"
                val hasNoExtraParts = uri.userInfo == null && uri.query == null && uri.fragment == null
                return uri.scheme?.lowercase() == "http" &&
                    isLoopback &&
                    uri.port == expectedPort &&
                    pathIsRoot &&
                    hasNoExtraParts
            }

            /**
             * Parses a raw command-line argument string into discrete tokens,
             * safely handling whitespace and single/double quotes.
             */
            internal fun parseCommandLineArguments(raw: String): List<String> {
                if (raw.isBlank()) return emptyList()
                val args = mutableListOf<String>()
                val current = StringBuilder()
                var inQuote: Char? = null
                var escapeNext = false

                for (ch in raw.trim()) {
                    if (escapeNext) {
                        current.append(ch)
                        escapeNext = false
                    } else if (ch == '\\') {
                        escapeNext = true
                    } else if (inQuote != null) {
                        if (ch == inQuote) {
                            inQuote = null
                        } else {
                            current.append(ch)
                        }
                    } else if (ch == '\'' || ch == '"') {
                        inQuote = ch
                    } else if (ch.isWhitespace()) {
                        if (current.isNotEmpty()) {
                            args.add(current.toString())
                            current.clear()
                        }
                    } else {
                        current.append(ch)
                    }
                }
                if (current.isNotEmpty()) {
                    args.add(current.toString())
                }
                return args
            }
        }
    }
