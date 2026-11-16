package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gap2x4CatalogTest {
    private val specs = Gap2x4Catalog.specs

    @Test fun idsUnique() = assertEquals(specs.size, specs.map { it.id }.toSet().size)

    @Test fun enoughSpecs() = assertTrue(specs.size >= 15)

    @Test fun labelsNotBlank() = assertTrue(specs.all { it.label.isNotBlank() })

    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures) assertTrue(s.id, g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun signaturesSniffToOwnSpec() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            assertEquals(s.id, sn.sniff(g.sample(), "x." + s.extensions.first()).id)
        }
    }
}
