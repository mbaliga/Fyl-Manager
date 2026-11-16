package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatSniffer
import org.junit.Assert.*
import org.junit.Test

class SourceCodeCatalogTest {
    private val specs = SourceCodeCatalog.specs

    @Test fun idsUnique() {
        val dup = specs.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate ids $dup", dup.isEmpty())
    }

    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures) assertTrue(s.id, g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun signaturesSniffToSelf() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sn.sniff(g.sample(), "x." + s.extensions.first()).id)
    }

    @Test fun enoughSpecs() { assertTrue("only ${specs.size}", specs.size >= 400) }

    @Test fun labelsNotBlank() { for (s in specs) assertTrue(s.id, s.label.isNotBlank()) }

    @Test fun extensionsUnique() {
        val dup = specs.flatMap { s -> s.extensions.map { it to s.id } }
            .groupBy({ it.first }, { it.second }).filter { it.value.size > 1 }
        assertTrue("dup exts $dup", dup.isEmpty())
    }
}
