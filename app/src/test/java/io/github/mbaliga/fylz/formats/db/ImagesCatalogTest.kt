package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class ImagesCatalogTest {
    private val specs = ImagesCatalog.specs

    @Test fun idsUnique() {
        val dup = specs.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate ids $dup", dup.isEmpty())
    }

    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures)
            assertTrue(s.id, g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun signaturesSniffToOwnId() {
        val sniffer = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            assertEquals(s.id, sniffer.sniff(g.sample(), "x." + s.extensions.first()).id)
        }
    }

    @Test fun enoughSpecs() = assertTrue("count=${specs.size}", specs.size >= 160)

    @Test fun labelsPresent() {
        for (s in specs) assertTrue(s.id, s.label.isNotBlank())
    }
}
