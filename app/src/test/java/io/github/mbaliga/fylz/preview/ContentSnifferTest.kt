package io.github.mbaliga.fylz.preview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0.9, defect 8/10: `.ts` is TypeScript source or an MPEG transport stream depending on
 * content, not extension/MIME -- [ContentSniffer.isMpegTs] is the disambiguator. */
class ContentSnifferTest {

    /** Three real MPEG-TS packets (188 bytes each): sync byte `0x47`, then arbitrary payload
     * bytes that are never themselves `0x47` at a sync offset, exactly as a real capture would
     * have packet headers/payload following each sync byte. */
    private fun mpegTsHeader(): ByteArray {
        val packet = ByteArray(188) { index -> if (index == 0) 0x47 else (index % 251).toByte() }
        return packet + packet + packet
    }

    @Test
    fun `a real MPEG-TS header is recognized`() {
        assertTrue(ContentSniffer.isMpegTs(mpegTsHeader()))
    }

    @Test
    fun `a TypeScript source header is not mistaken for MPEG-TS`() {
        val source = """
            export function greet(name: string): string {
              return `Hello, ${'$'}{name}`;
            }
        """.trimIndent().repeat(4).toByteArray(Charsets.UTF_8)
        assertFalse(ContentSniffer.isMpegTs(source))
    }

    @Test
    fun `a header no longer than one packet can never be confirmed`() {
        val onePacket = ByteArray(188) { if (it == 0) 0x47 else 0 }
        assertFalse(ContentSniffer.isMpegTs(onePacket))
    }

    @Test
    fun `a coincidental leading sync byte without the repeat at offset 188 is rejected`() {
        val header = ByteArray(400) { 0 }
        header[0] = 0x47
        // Offset 188 is NOT 0x47, so this is not the repeating MPEG-TS pattern.
        assertFalse(ContentSniffer.isMpegTs(header))
    }

    @Test
    fun `two confirmed sync bytes are enough when a third packet is not available`() {
        val packet = ByteArray(188) { if (it == 0) 0x47 else 0 }
        val header = packet + packet // exactly 376 bytes: no offset-376 byte exists to check
        assertTrue(ContentSniffer.isMpegTs(header))
    }
}
