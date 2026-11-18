package io.github.mbaliga.fylz.formats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackTest {

    @Test fun `hex dump lays out offset, bytes and ascii`() {
        val dump = Fallback.hexDump("Hello, hex dump!".toByteArray())
        val line = dump.lines().first()
        assertTrue(line.startsWith("00000000  48 65 6c 6c 6f 2c 20 68"))
        assertTrue(line.endsWith("|Hello, hex dump!|"))
    }

    @Test fun `a short last line is padded so the ascii column lines up`() {
        val lines = Fallback.hexDump(ByteArray(20) { 0x41 }).lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertEquals(lines[0].indexOf('|'), lines[1].indexOf('|'))
    }

    @Test fun `non printable bytes show as dots and base offset is honoured`() {
        val dump = Fallback.hexDump(byteArrayOf(0, 1, 0x7F, 0x41), baseOffset = 0x1000)
        assertTrue(dump.startsWith("00001000  "))
        assertTrue(dump.contains("|...A|"))
    }

    @Test fun `dump is capped`() {
        val dump = Fallback.hexDump(ByteArray(10_000), maxBytes = 64)
        assertEquals(4, dump.lines().filter { it.isNotEmpty() }.size)
    }

    @Test fun `strings finds ascii runs with their offsets`() {
        val data = byteArrayOf(0, 0) + "hello".toByteArray() + byteArrayOf(0, 1) + "abc".toByteArray() + byteArrayOf(0) + "world!".toByteArray()
        val found = Fallback.strings(data)
        assertEquals(listOf("hello", "world!"), found.map { it.text })
        assertEquals(2L, found[0].offset)
    }

    @Test fun `strings finds utf16le runs`() {
        val utf16 = "Title".flatMap { listOf(it.code.toByte(), 0.toByte()) }.toByteArray()
        val found = Fallback.strings(byteArrayOf(1, 2) + utf16 + byteArrayOf(9, 9))
        assertTrue(found.any { it.text == "Title" })
    }

    @Test fun `strings respects the limit and min length`() {
        val data = (1..50).flatMap { "word$it".toByteArray().toList() + listOf(0.toByte()) }.toByteArray()
        assertEquals(10, Fallback.strings(data, limit = 10).size)
        assertTrue(Fallback.strings("ab\u0000cd".toByteArray(), minLength = 4).isEmpty())
    }

    @Test fun `entropy spans zero to eight`() {
        assertEquals(0.0, Fallback.entropy(ByteArray(0)), 1e-9)
        assertEquals(0.0, Fallback.entropy(ByteArray(1000)), 1e-9)
        val uniform = ByteArray(256 * 8) { (it % 256).toByte() }
        assertEquals(8.0, Fallback.entropy(uniform), 1e-6)
    }

    @Test fun `entropy descriptions track the bands`() {
        assertTrue(Fallback.describeEntropy(0.2).contains("repetitive"))
        assertTrue(Fallback.describeEntropy(3.0).contains("text"))
        assertTrue(Fallback.describeEntropy(7.99).contains("encrypted"))
    }
}
