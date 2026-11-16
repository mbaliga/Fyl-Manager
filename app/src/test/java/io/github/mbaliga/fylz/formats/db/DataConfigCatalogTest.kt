package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataConfigCatalogTest {
    private val specs = DataConfigCatalog.specs

    @Test fun idsAreUnique() {
        val dup = specs.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate ids: $dup", dup.isEmpty())
    }

    @Test fun atLeast160Specs() {
        assertTrue("only ${specs.size}", specs.size >= 160)
    }

    @Test fun labelsNotBlank() {
        assertTrue(specs.all { it.label.isNotBlank() && it.extensions.isNotEmpty() })
    }

    @Test fun signaturesWithinHeaderLimit() {
        for (s in specs) for (g in s.signatures)
            assertTrue("${s.id} signature too far", g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun containersExist() {
        val ids = specs.map { it.id }.toSet()
        for (s in specs) s.containerId?.let { assertTrue("${s.id} -> $it", it in ids) }
    }

    @Test fun signaturesSniffToOwnFormat() {
        val sniffer = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "x." + s.extensions.first())
            assertEquals("signature of ${s.id}", s.id, r.spec?.id)
        }
    }
}
