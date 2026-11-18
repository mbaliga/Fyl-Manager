package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap2x3CatalogTest {
    private val specs = Gap2x3Catalog.specs

    @Test fun idsUnique() {
        val dup = specs.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate ids: $dup", dup.isEmpty())
    }

    @Test fun atLeastSixty() = assertTrue(specs.size >= 60)

    @Test fun labelsNotBlank() = specs.forEach { assertTrue(it.id, it.label.isNotBlank()) }

    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures)
            assertTrue(s.id, g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun signaturesSniffToOwnSpec() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            assertEquals(s.id, s.id, sn.sniff(g.sample(), "x." + s.extensions.first()).spec?.id)
        }
    }
}
