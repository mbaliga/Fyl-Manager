package io.github.mbaliga.fylz.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TruncateToUtf8BytesTest {

    @Test
    fun `text already within budget is returned unchanged`() {
        assertEquals("hello", truncateToUtf8Bytes("hello", 255))
    }

    @Test
    fun `ascii text is cut exactly at the byte budget`() {
        val text = "a".repeat(300)

        val result = truncateToUtf8Bytes(text, 255)

        assertEquals(255, result.length)
        assertEquals("a".repeat(255), result)
    }

    @Test
    fun `a multi-byte character is dropped whole rather than split`() {
        // "€" is 3 bytes in UTF-8 (0xE2 0x82 0xAC). A budget landing inside it must discard the
        // whole character, not the leading byte alone -- a lone lead byte is invalid UTF-8.
        val text = "a".repeat(254) + "€"

        val result = truncateToUtf8Bytes(text, 255)

        assertEquals("a".repeat(254), result)
        assertEquals(254, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `an emoji surrogate pair is dropped whole, never left as a lone surrogate`() {
        // U+1F600 GRINNING FACE is 4 bytes in UTF-8 and a 2-unit UTF-16 surrogate pair --
        // exactly the case String.take(255) (code-unit counting) handles differently than a
        // byte-budget cut must.
        val emoji = "😀"
        val text = "a".repeat(252) + emoji

        val result = truncateToUtf8Bytes(text, 255)

        assertEquals("a".repeat(252), result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 255)
        // A real result must be re-encodable without error -- decodeToString's default
        // throwOnInvalidSequence=false would otherwise have silently swapped in U+FFFD.
        assertTrue(result.none { it == '�' })
    }

    @Test
    fun `a name entirely of multi-byte characters still fits its byte budget`() {
        val text = "本".repeat(200) // 3 bytes each in UTF-8

        val result = truncateToUtf8Bytes(text, 255)

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals(85, result.length) // 85 * 3 = 255 bytes exactly
    }
}
