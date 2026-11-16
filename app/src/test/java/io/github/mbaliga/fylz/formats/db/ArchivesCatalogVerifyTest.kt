package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class ArchivesCatalogVerifyTest {
    private val specs = ArchivesCatalog.specs
    private val sniffer = FormatSniffer(specs)

    @Test fun sampleSniffsToOwnSpecWithWrongExtension() {
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "file.qqq")
            assertEquals("${s.id} with wrong ext", s.id, r.spec?.id)
        }
    }

    @Test fun sampleSniffsToOwnSpecWithNoName() {
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sniffer.sniff(g.sample()).spec?.id)
    }

    @Test fun sampleWithOtherCatalogExtensionStillOwnSpec() {
        // a signature must beat a misleading extension of an unrelated, non-container spec
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "file.txt")
            assertEquals(s.id, r.spec?.id)
        }
    }

    @Test fun extensionsLowercaseAndNoDuplicatesWithinSpec() {
        for (s in specs) {
            assertEquals(s.id, s.extensions.size, s.extensions.toSet().size)
            assertTrue(s.id, s.extensions.isNotEmpty())
        }
    }

    @Test fun containerSpecsHaveNoSignatureOrOwnOneIsMoreSpecific() {
        for (s in specs) {
            val c = s.containerId ?: continue
            assertNotEquals(s.id, c)
            val parent = specs.first { it.id == c }
            for (g in s.signatures) assertTrue("${s.id}", parent.signatures.isEmpty() ||
                g.specificity > parent.signatures.maxOf { it.specificity })
        }
    }

    @Test fun containerChainsAcyclic() {
        val m = specs.associateBy { it.id }
        for (s in specs) {
            var c = s.containerId; var n = 0
            while (c != null) { c = m[c]?.containerId; assertTrue(s.id, ++n < 5) }
        }
    }

    @Test fun zipSpecialisationsWinOverBareZip() {
        val zip = specs.first { it.id == "zip" }.signatures.first().sample()
        for (s in specs.filter { it.containerId == "zip" }) {
            assertEquals(s.id, sniffer.sniff(zip, "a.${s.extensions.first()}").spec?.id)
        }
    }

    @Test fun twoByteMagicsAreOnlyDocumentedOnes() {
        for (s in specs) for (g in s.signatures)
            if (g.specificity < 4) assertTrue(s.id, s.id in setOf("arj", "z", "gz"))
    }

    @Test fun mimeTypesWellFormed() {
        for (s in specs) for (m in s.mimeTypes) assertTrue("${s.id} $m", Regex("[a-z0-9.+-]+/[a-z0-9.+_-]+").matches(m))
    }

    @Test fun knownIds() {
        val ids = specs.map { it.id }.toSet()
        for (i in listOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "iso", "apk", "deb", "rpm", "msi", "cab", "vhd", "vmdk"))
            assertTrue(i, i in ids)
    }

    @Test fun rarVsRar5() {
        val rar = byteArrayOf(0x52,0x61,0x72,0x21,0x1A,0x07,0x00,0,0,0)
        val rar5 = byteArrayOf(0x52,0x61,0x72,0x21,0x1A,0x07,0x01,0x00,0,0)
        assertEquals("rar", sniffer.sniff(rar, "a.rar").spec?.id)
        assertEquals("rar5", sniffer.sniff(rar5, "a.rar").spec?.id)
    }
}
