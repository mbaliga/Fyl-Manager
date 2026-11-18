package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap1x2CatalogVerifyTest {
    private val specs = Gap1x2Catalog.specs
    private val sniffer = FormatSniffer(specs)

    @Test fun sampleSniffsToOwnSpecWithWrongExtension() {
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "file.dat")
            assertEquals(s.id, r.spec?.id)
            val r2 = sniffer.sniff(g.sample(), "file.txt")
            assertEquals(s.id, r2.spec?.id)
        }
    }
    @Test fun extensionsLowercaseNoDotsUnique() {
        val seen = HashMap<String, String>()
        for (s in specs) for (e in s.extensions) {
            assertEquals(e, e.lowercase()); assertFalse(e.startsWith("."))
            assertNull("dup ext $e in ${s.id} and ${seen[e]}", seen.put(e, s.id))
        }
    }
    @Test fun idsLowercase() = assertTrue(specs.all { it.id == it.id.lowercase() })
    @Test fun idMatchesPrimaryExtension() {
        for (s in specs.filter { it.id != "figma" }) assertEquals("id ${s.id} vs primary ext", s.id, s.extensions.first())
    }
    @Test fun signaturesLongEnough() {
        for (s in specs) for (g in s.signatures) assertTrue(s.id, g.parts.sumOf { it.bytes.size } >= 4)
    }
    @Test fun noRandomZeroHeaderMatches() {
        val zeros = ByteArray(64)
        for (s in specs) for (g in s.signatures) assertFalse(s.id, g.matches(zeros))
    }
}
