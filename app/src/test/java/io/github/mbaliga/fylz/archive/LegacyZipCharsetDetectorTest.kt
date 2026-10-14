package io.github.mbaliga.fylz.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M3.7: every [ArchiveNameEncoding]'s JDK charset name actually resolves on this JDK (the "quick
 * smoke test" the brief asks for -- a typo here would otherwise surface only the first time a
 * lossy name of that family was opened, far from where the typo was made), and
 * [LegacyZipCharsetDetector]'s heuristic picks the family its own doc comment says it can. Plain
 * JVM: nothing here touches Android.
 */
class LegacyZipCharsetDetectorTest {

    @Test
    fun `every named encoding's charset resolves on this JDK, AUTO has none`() {
        assertNull(ArchiveNameEncoding.AUTO.charset)
        assertEquals("IBM437", ArchiveNameEncoding.CP437.charset?.name())
        assertEquals("IBM866", ArchiveNameEncoding.CP866.charset?.name())
        assertEquals("GBK", ArchiveNameEncoding.GBK.charset?.name())
        assertEquals("Shift_JIS", ArchiveNameEncoding.SHIFT_JIS.charset?.name())
        assertEquals("EUC-KR", ArchiveNameEncoding.EUC_KR.charset?.name())
    }

    @Test
    fun `a single isolated high byte outside CP866's own signal range is guessed CP437`() {
        // "café.txt" under CP437: only 0x82 is non-ASCII, and it sits outside 0x90-0x9F.
        assertEquals(ArchiveNameEncoding.CP437, LegacyZipCharsetDetector.detect(bytesOf(0x63, 0x61, 0x66, 0x82, 0x2E, 0x74, 0x78, 0x74)))
    }

    @Test
    fun `a single isolated high byte inside CP866's own signal range is guessed CP866`() {
        // An isolated byte in 0x90-0x9F (CP-866's own "second half" of Cyrillic uppercase)
        // surrounded by ASCII, so no double-byte pattern can fire first.
        assertEquals(ArchiveNameEncoding.CP866, LegacyZipCharsetDetector.detect(bytesOf(0x54, 0x92, 0x2D, 0x66)))
    }

    @Test
    fun `a shift-jis lead and trail byte pair is guessed shift-jis`() {
        assertEquals(ArchiveNameEncoding.SHIFT_JIS, LegacyZipCharsetDetector.detect(bytesOf(0x82, 0xA0)))
    }

    @Test
    fun `a gbk-only lead and trail byte pair is guessed gbk`() {
        // 0xB0 is outside Shift-JIS's lead ranges and outside EUC-KR's trail range (0x41 < 0xA1),
        // so this pair reaches the GBK check and no earlier one.
        assertEquals(ArchiveNameEncoding.GBK, LegacyZipCharsetDetector.detect(bytesOf(0xB0, 0x41)))
    }

    @Test
    fun `an euc-kr lead and trail byte pair is guessed euc-kr, not the broader gbk pattern it also matches`() {
        assertEquals(ArchiveNameEncoding.EUC_KR, LegacyZipCharsetDetector.detect(bytesOf(0xB0, 0xB1)))
    }

    @Test
    fun `an empty or all-ascii byte array is guessed CP437, the default`() {
        assertEquals(ArchiveNameEncoding.CP437, LegacyZipCharsetDetector.detect(ByteArray(0)))
        assertEquals(ArchiveNameEncoding.CP437, LegacyZipCharsetDetector.detect("plain.txt".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `decode under AUTO resolves through detect, decode under a named encoding does not`() {
        val cp437Bytes = bytesOf(0x63, 0x61, 0x66, 0x82, 0x2E, 0x74, 0x78, 0x74)
        assertEquals("café.txt", LegacyZipCharsetDetector.decode(cp437Bytes, ArchiveNameEncoding.AUTO))
        assertEquals("café.txt", LegacyZipCharsetDetector.decode(cp437Bytes, ArchiveNameEncoding.CP437))
        // Forcing the wrong charset still decodes to *something* (a display feature, never a
        // refusal) -- just not "café.txt": byte 0x82 is CP-866's Cyrillic capital В (U+0412).
        assertEquals("cafВ.txt", LegacyZipCharsetDetector.decode(cp437Bytes, ArchiveNameEncoding.CP866))
    }

    private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
