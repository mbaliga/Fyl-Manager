package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap2x1CatalogTest {
    private val specs = Gap2x1Catalog.specs

    @Test fun idsUnique() = assertEquals(specs.size, specs.map { it.id }.toSet().size)
    @Test fun enough() = assertTrue(specs.size >= 35)
    @Test fun labels() = assertTrue(specs.all { it.label.isNotBlank() })

    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures)
            assertTrue("${s.id} too long", g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun signaturesSniffToSelf() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sn.sniff(g.sample(), "x." + s.extensions.first()).id)
    }
}
