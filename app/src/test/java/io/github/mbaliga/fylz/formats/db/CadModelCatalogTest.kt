package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class CadModelCatalogTest {
    private val specs = CadModelCatalog.specs

    @Test fun idsUnique() {
        val dup = specs.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("dup ids $dup", dup.isEmpty())
    }

    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures)
            assertTrue(s.id, g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun signaturesSniffToOwner() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            assertEquals(s.id, sn.sniff(g.sample(), "x." + s.extensions.first()).spec?.id)
        }
    }

    @Test fun enough() = assertTrue(specs.size >= 140)

    @Test fun labels() = assertTrue(specs.all { it.label.isNotBlank() })
}
