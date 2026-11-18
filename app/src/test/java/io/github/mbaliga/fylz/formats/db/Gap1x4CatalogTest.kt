package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap1x4CatalogTest {
    private val specs = Gap1x4Catalog.specs

    @Test fun idsUnique() = assertEquals(specs.size, specs.map { it.id }.toSet().size)
    @Test fun atLeastTwenty() = assertTrue(specs.size >= 20)
    @Test fun labelsNotBlank() = specs.forEach { assertTrue(it.id, it.label.isNotBlank()) }
    @Test fun signaturesWithinHeader() = specs.forEach { s ->
        s.signatures.forEach { assertTrue(s.id, it.end <= FormatSniffer.MAX_HEADER_BYTES) }
    }
    @Test fun signaturesSniffToOwnId() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sn.sniff(g.sample(), "x." + s.extensions.first()).id)
    }
}
