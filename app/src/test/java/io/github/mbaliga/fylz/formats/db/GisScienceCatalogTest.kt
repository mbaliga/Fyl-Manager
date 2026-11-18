package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatSniffer
import org.junit.Assert.*
import org.junit.Test

class GisScienceCatalogTest {
    private val specs = GisScienceCatalog.specs

    @Test fun idsUnique() {
        val dup = specs.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate ids $dup", dup.isEmpty())
    }

    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures)
            assertTrue(s.id, g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun signaturesSniffToSelf() {
        val sniffer = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "x." + s.extensions.first())
            assertEquals("spec ${s.id}", s.id, r.spec?.id)
        }
    }

    @Test fun enoughSpecs() = assertTrue("only ${specs.size}", specs.size >= 140)

    @Test fun labelsNotBlank() = assertTrue(specs.all { it.label.isNotBlank() })
}
