package com.danielealbano.androidremotecontrolmcp.services.shell

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Command-line tools shipped in the APK as `lib<name>.so` (only `.so` files are extracted, to the
 * executable native library dir), exposed to shell commands under their real names.
 */
object BundledTools {
    const val BIN_DIR = "/data/local/tmp/bin"

    /** Command name to the library file it ships as. */
    val TOOLS = mapOf("curl" to "libcurl.so")

    /**
     * Points `binDir/<name>` at the APK's copy of each tool. Re-run before each command because the
     * native library dir moves on every app update. A file of that name that isn't a symlink is the
     * user's own and is left alone. Returns the names that could not be linked.
     */
    fun link(
        nativeLibDir: String,
        binDir: Path = Paths.get(BIN_DIR),
    ): List<String> =
        TOOLS.mapNotNull { (name, lib) ->
            val target = Paths.get(nativeLibDir, lib)
            val link = binDir.resolve(name)
            try {
                if (!Files.isRegularFile(target)) return@mapNotNull name
                if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target) return@mapNotNull null
                if (Files.exists(link) && !Files.isSymbolicLink(link)) return@mapNotNull name
                Files.createDirectories(binDir)
                Files.deleteIfExists(link)
                Files.createSymbolicLink(link, target)
                null
            } catch (_: IOException) {
                // Lost a race with a concurrent command doing the same, or binDir isn't writable.
                name.takeUnless { Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target }
            }
        }

    /** Appended: anything running as shell can write to [BIN_DIR], so it must never shadow system commands. */
    fun path(current: String?): String = if (current.isNullOrEmpty()) BIN_DIR else "$current:$BIN_DIR"
}
