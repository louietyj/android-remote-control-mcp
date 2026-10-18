package com.danielealbano.androidremotecontrolmcp.services.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@DisplayName("CappedOutputCollector")
class CappedOutputCollectorTest {
    private fun collect(
        bytes: ByteArray,
        cap: Int,
    ): CappedOutput = CappedOutputCollector(cap).apply { drain(ByteArrayInputStream(bytes)) }.snapshot()

    @Test
    fun `output under the cap is kept whole`() {
        val out = collect("hello\n".toByteArray(), cap = 64)
        assertEquals("hello\n", out.text)
        assertFalse(out.truncated)
    }

    @Test
    fun `output exactly at the cap is not truncated`() {
        val out = collect("abcd".toByteArray(), cap = 4)
        assertEquals("abcd", out.text)
        assertFalse(out.truncated)
    }

    @Test
    fun `output over the cap is truncated and fully drained`() {
        val input = ByteArrayInputStream(ByteArray(100_000) { 'x'.code.toByte() })
        val collector = CappedOutputCollector(10)

        collector.drain(input)

        val out = collector.snapshot()
        assertEquals("x".repeat(10), out.text)
        assertTrue(out.truncated)
        assertEquals(0, input.available(), "Excess bytes must be drained so the producer never blocks")
    }

    @Test
    fun `empty stream yields empty text`() {
        val out = collect(ByteArray(0), cap = 64)
        assertEquals("", out.text)
        assertFalse(out.truncated)
    }

    @Test
    fun `invalid UTF-8 is decoded leniently`() {
        val out = collect(byteArrayOf('a'.code.toByte(), 0xC3.toByte()), cap = 64)
        assertEquals("a�", out.text)
    }

    @Test
    fun `IOException mid-stream keeps bytes read so far`() {
        val failing =
            object : InputStream() {
                private var sent = false

                override fun read(): Int = throw IOException("closed")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (sent) throw IOException("closed")
                    sent = true
                    b[off] = 'z'.code.toByte()
                    return 1
                }
            }
        val collector = CappedOutputCollector(64)

        collector.drain(failing)

        assertEquals("z", collector.snapshot().text)
    }
}
