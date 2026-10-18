package com.danielealbano.androidremotecontrolmcp.services.shell

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Captured stream output, possibly truncated to the collector's byte cap. */
data class CappedOutput(
    val text: String,
    val truncated: Boolean,
)

/**
 * Keeps the first [maxBytes] of a stream and drains the rest so the producer never blocks.
 * [snapshot] may be called while [drain] is still blocked (a background child holding the pipe).
 */
class CappedOutputCollector(
    private val maxBytes: Int,
) {
    private val buffer = ByteArrayOutputStream()
    private var truncated = false

    fun drain(input: InputStream) {
        val chunk = ByteArray(CHUNK_SIZE)
        try {
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                append(chunk, read)
            }
        } catch (_: IOException) {
            // Stream closed under us (process destroyed); keep what was captured.
        }
    }

    @Synchronized
    private fun append(
        chunk: ByteArray,
        length: Int,
    ) {
        val room = maxBytes - buffer.size()
        if (length > room) truncated = true
        if (room > 0) buffer.write(chunk, 0, minOf(length, room))
    }

    @Synchronized
    fun snapshot(): CappedOutput = CappedOutput(buffer.toString(Charsets.UTF_8.name()), truncated)

    private companion object {
        const val CHUNK_SIZE = 8192
    }
}
