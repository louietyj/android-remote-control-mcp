package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellExecutor
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellResult
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellUnavailableException
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("ShellHandler")
class ShellToolsTest {
    private lateinit var shellExecutor: ShellExecutor
    private lateinit var handler: ShellHandler

    private val okResult =
        ShellResult(
            exitCode = 0,
            stdout = "uid=2000(shell)\n",
            stderr = "",
            stdoutTruncated = false,
            stderrTruncated = false,
            timedOut = false,
            uid = 2000,
        )

    @BeforeEach
    fun setUp() {
        shellExecutor = mockk()
        handler =
            ShellHandler(
                shellExecutor,
                PrivacyToolTestDoubles.passthroughGate(),
                PrivacyToolTestDoubles.identitySubstitutor(),
            )
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun args(
        command: String? = "id",
        timeoutMs: Long? = null,
    ) = buildJsonObject {
        command?.let { put("command", it) }
        timeoutMs?.let { put("timeout_ms", it) }
    }

    private fun parse(text: String): JsonObject {
        val body = text.removePrefix(McpToolUtils.UNTRUSTED_CONTENT_WARNING).trim()
        return Json.parseToJsonElement(body).jsonObject
    }

    @Test
    fun `returns JSON with all fields behind untrusted warning`() =
        runTest {
            coEvery { shellExecutor.exec("id", ShellHandler.DEFAULT_TIMEOUT_MS) } returns Result.success(okResult)

            val text = (handler.execute(args()).content[0] as TextContent).text

            assertTrue(text.startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING))
            val json = parse(text)
            assertEquals(0, json["exit_code"]!!.jsonPrimitive.int)
            assertEquals("uid=2000(shell)\n", json["stdout"]!!.jsonPrimitive.content)
            assertEquals("", json["stderr"]!!.jsonPrimitive.content)
            assertEquals(false, json["timed_out"]!!.jsonPrimitive.boolean)
            assertEquals(false, json["stdout_truncated"]!!.jsonPrimitive.boolean)
            assertEquals(false, json["stderr_truncated"]!!.jsonPrimitive.boolean)
            assertEquals(2000, json["uid"]!!.jsonPrimitive.int)
        }

    @Test
    fun `passes explicit timeout through`() =
        runTest {
            coEvery { shellExecutor.exec("id", 5000L) } returns Result.success(okResult)

            handler.execute(args(timeoutMs = 5000L))

            coVerify { shellExecutor.exec("id", 5000L) }
        }

    @Test
    fun `reports timeout and truncation flags`() =
        runTest {
            coEvery { shellExecutor.exec(any(), any()) } returns
                Result.success(okResult.copy(exitCode = 137, timedOut = true, stdoutTruncated = true))

            val json = parse((handler.execute(args()).content[0] as TextContent).text)

            assertEquals(137, json["exit_code"]!!.jsonPrimitive.int)
            assertEquals(true, json["timed_out"]!!.jsonPrimitive.boolean)
            assertEquals(true, json["stdout_truncated"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `missing command throws InvalidParams`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> { handler.execute(args(command = null)) }
        }

    @Test
    fun `blank command throws InvalidParams`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> { handler.execute(args(command = "   ")) }
        }

    @Test
    fun `timeout_ms out of range throws InvalidParams`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> { handler.execute(args(timeoutMs = 0L)) }
            assertThrows<McpToolException.InvalidParams> {
                handler.execute(args(timeoutMs = ShellHandler.MAX_TIMEOUT_MS + 1))
            }
        }

    @Test
    fun `shizuku unavailable maps to ActionFailed`() =
        runTest {
            coEvery { shellExecutor.exec(any(), any()) } returns
                Result.failure(ShellUnavailableException("Shizuku is not running"))

            val e = assertThrows<McpToolException.ActionFailed> { handler.execute(args()) }
            assertTrue(e.message!!.contains("Shizuku is not running"))
        }

    @Test
    fun `permission not granted maps to PermissionDenied`() =
        runTest {
            coEvery { shellExecutor.exec(any(), any()) } returns Result.failure(SecurityException("not granted"))

            assertThrows<McpToolException.PermissionDenied> { handler.execute(args()) }
        }

    @Test
    fun `stdout and stderr pass through the privacy gate`() =
        runTest {
            val gate = mockk<PrivacyToolGate>()
            coEvery { gate.text(any(), "stdout") } returns "[REDACTED_OUT]"
            coEvery { gate.text(any(), "stderr") } returns "[REDACTED_ERR]"
            coEvery { shellExecutor.exec(any(), any()) } returns Result.success(okResult.copy(stderr = "secret"))
            val gated = ShellHandler(shellExecutor, gate, PrivacyToolTestDoubles.identitySubstitutor())

            val json = parse((gated.execute(args()).content[0] as TextContent).text)

            assertEquals("[REDACTED_OUT]", json["stdout"]!!.jsonPrimitive.content)
            assertEquals("[REDACTED_ERR]", json["stderr"]!!.jsonPrimitive.content)
        }

    @Test
    fun `placeholders in command are substituted before execution`() =
        runTest {
            val substitutor = mockk<PlaceholderSubstitutor>()
            every { substitutor.substitute("echo [EMAIL_1]") } returns "echo a@b.c"
            coEvery { shellExecutor.exec("echo a@b.c", any()) } returns Result.success(okResult)
            val substituting = ShellHandler(shellExecutor, PrivacyToolTestDoubles.passthroughGate(), substitutor)

            substituting.execute(args(command = "echo [EMAIL_1]"))

            coVerify { shellExecutor.exec("echo a@b.c", any()) }
        }

    @Test
    fun `register adds tool with prefixed name`() {
        val mockServer = mockk<Server>(relaxed = true)
        val nameSlot = slot<String>()
        every {
            mockServer.addTool(name = capture(nameSlot), description = any(), inputSchema = any(), handler = any())
        } returns Unit

        handler.register(LoggedToolRegistrar(mockServer, RecordingServerLogRepository()), "android_")

        assertEquals("android_shell", nameSlot.captured)
    }
}
