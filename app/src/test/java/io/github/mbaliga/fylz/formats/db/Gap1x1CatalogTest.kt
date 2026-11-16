package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap1x1CatalogTest {
    private val specs = Gap1x1Catalog.specs

    @Test fun idsUnique() = assertEquals(specs.size, specs.map { it.id }.toSet().size)
    @Test fun enoughSpecs() = assertTrue(specs.size >= 42)
    @Test fun labelsNotBlank() = assertTrue(specs.all { it.label.isNotBlank() })
    @Test fun signaturesWithinHeader() =
        assertTrue(specs.flatMap { it.signatures }.all { it.end <= FormatSniffer.MAX_HEADER_BYTES })

    @Test fun signaturesSniffToOwnSpec() {
        val sniffer = FormatSniffer(specs)
        for (s in specs) for (sg in s.signatures) {
            val r = sniffer.sniff(sg.sample(), "x." + s.extensions.first())
            assertEquals(s.id, r.spec?.id)
        }
    }
}
