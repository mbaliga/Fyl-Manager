package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap2x2CatalogTest {
    private val specs = Gap2x2Catalog.specs

    @Test fun idsUnique() = assertEquals(specs.size, specs.map { it.id }.toSet().size)
    @Test fun atLeast20() = assertTrue(specs.size >= 20)
    @Test fun labels() = assertTrue(specs.all { it.label.isNotBlank() })
    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures)
            assertTrue(s.id, g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }
    @Test fun sniffRoundTrip() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sn.sniff(g.sample(), "x." + s.extensions.first()).id)
    }
}
