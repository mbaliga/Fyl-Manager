package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap2x6CatalogTest {
    private val specs = Gap2x6Catalog.specs

    @Test fun idsUnique() = assertEquals(specs.size, specs.map { it.id }.toSet().size)
    @Test fun enoughSpecs() = assertTrue(specs.size >= 38)
    @Test fun labelsNotBlank() = assertTrue(specs.all { it.label.isNotBlank() })
    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures) assertTrue(s.id, g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }
    @Test fun sniffRoundTrip() {
        val sniffer = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            assertEquals(s.id, sniffer.sniff(g.sample(), "x." + s.extensions.first()).id)
        }
    }
}
