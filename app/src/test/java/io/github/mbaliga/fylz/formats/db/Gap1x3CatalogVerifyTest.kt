package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap1x3CatalogVerifyTest {
    private val specs = Gap1x3Catalog.specs
    private val sniffer = FormatSniffer(specs)

    @Test fun sampleSniffsWithWrongExtension() {
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "file.zzzwrong")
            assertEquals(s.id, r.id)
            assertEquals(Confidence.MAGIC, r.confidence)
            assertTrue(r.extensionMismatch)
        }
    }

    @Test fun sampleSniffsWithOtherSpecsExtension() {
        for (s in specs) for (g in s.signatures) {
            val other = specs.first { it.id != s.id && it.extensions.none { e -> e in s.extensions } }
            assertEquals(s.id, sniffer.sniff(g.sample(), "x." + other.extensions.first()).id)
        }
    }

    @Test fun extensionOnlyWorksForEverySpec() {
        for (s in specs) for (e in s.extensions) {
            val r = sniffer.sniff(ByteArray(16) { 0x55 }, "disk.$e")
            assertNotNull(e, r.spec)
            assertTrue(e, r.spec!!.extensions.contains(e))
        }
    }

    @Test fun noExtensionClaimedTwiceInside() {
        val seen = HashMap<String, String>()
        for (s in specs) for (e in s.extensions) {
            val prev = seen.put(e, s.id)
            assertTrue("ext $e in ${prev} and ${s.id}", prev == null)
        }
    }

    @Test fun randomBytesNeverMatch() {
        val rnd = java.util.Random(7)
        repeat(2000) {
            val b = ByteArray(64).also { rnd.nextBytes(it) }
            assertNull(sniffer.sniff(b).spec)
        }
    }

    @Test fun shortAndEmptyHeadersDoNotCrash() {
        assertNull(sniffer.sniff(ByteArray(0)).spec)
        assertNull(sniffer.sniff(ByteArray(3)).spec)
    }

    @Test fun primaryExtensionIsId() {
        for (s in specs.filter { it.id !in setOf("ps3psv", "ps1mcr") }) assertTrue(s.id, s.extensions.first() == s.id)
    }

    @Test fun knownMagicsAreExact() {
        fun hit(bytes: ByteArray, id: String) = assertEquals(id, sniffer.sniff(bytes).id)
        hit("C64File\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(8), "p00")
        hit("SINCLAIR".toByteArray() + ByteArray(8), "scl")
        hit("2IMG".toByteArray() + ByteArray(60), "2mg")
        hit("HXCPICFE".toByteArray() + ByteArray(8), "hfe")
    }

    /** ids already owned by other catalogs (ExecutablesCatalog "xex" = Xbox 360, AudioVideoCatalog "sap"). */
    @Test fun idsDoNotCollideWithOtherCatalogs() {
        val taken = setOf("xex", "sap")
        val clash = specs.map { it.id }.filter { it in taken }
        assertTrue("id collision with other catalogs: $clash", clash.isEmpty())
    }
}
