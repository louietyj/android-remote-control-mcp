package com.danielealbano.androidremotecontrolmcp.services.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("BundledTools")
class BundledToolsTest {
    @TempDir
    lateinit var tempDir: Path

    private val libDir get() = tempDir.resolve("lib").also { Files.createDirectories(it) }
    private val binDir get() = tempDir.resolve("bin")

    private fun shipCurl(dir: Path = libDir): Path = Files.write(dir.resolve("libcurl.so"), byteArrayOf(0x7F, 0x45))

    @Nested
    @DisplayName("link")
    inner class Link {
        @Test
        fun `creates bin dir and links the tool under its real name`() {
            val lib = shipCurl()

            val unlinked = BundledTools.link(libDir.toString(), binDir)

            assertEquals(emptyList<String>(), unlinked)
            assertEquals(lib, Files.readSymbolicLink(binDir.resolve("curl")))
        }

        @Test
        fun `repoints a stale link after the native library dir moved`() {
            val old = shipCurl()
            BundledTools.link(libDir.toString(), binDir)
            val movedDir = Files.createDirectories(tempDir.resolve("lib2"))
            val moved = shipCurl(movedDir)

            BundledTools.link(movedDir.toString(), binDir)

            assertEquals(moved, Files.readSymbolicLink(binDir.resolve("curl")))
            assertTrue(old != moved)
        }

        @Test
        fun `is a no-op when the link is already current`() {
            shipCurl()
            BundledTools.link(libDir.toString(), binDir)

            assertEquals(emptyList<String>(), BundledTools.link(libDir.toString(), binDir))
        }

        @Test
        fun `reports a tool the APK does not ship and creates nothing`() {
            assertEquals(listOf("curl"), BundledTools.link(libDir.toString(), binDir))
            assertFalse(Files.exists(binDir.resolve("curl"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }

        @Test
        fun `leaves a user's own file of the same name alone`() {
            shipCurl()
            Files.createDirectories(binDir)
            val mine = Files.write(binDir.resolve("curl"), byteArrayOf(1))

            assertEquals(listOf("curl"), BundledTools.link(libDir.toString(), binDir))
            assertFalse(Files.isSymbolicLink(mine))
            assertEquals(1, Files.readAllBytes(mine).single().toInt())
        }
    }

    @Nested
    @DisplayName("path")
    inner class PathValue {
        @Test
        fun `appends the bin dir after the system path`() {
            val path = BundledTools.path("/system/bin:/vendor/bin")

            assertEquals("/system/bin:/vendor/bin:${BundledTools.BIN_DIR}", path)
        }

        @Test
        fun `uses only the bin dir when there is no path`() {
            assertEquals(BundledTools.BIN_DIR, BundledTools.path(null))
            assertEquals(BundledTools.BIN_DIR, BundledTools.path(""))
        }
    }
}
