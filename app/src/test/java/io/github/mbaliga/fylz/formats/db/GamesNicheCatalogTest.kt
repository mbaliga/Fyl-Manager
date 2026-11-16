package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class GamesNicheCatalogTest {
    private val specs = GamesNicheCatalog.specs

    @Test fun idsUnique() {
        val dup = specs.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate ids $dup", dup.isEmpty())
    }

    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures)
            assertTrue("${s.id} ends at ${g.end}", g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun signaturesSniffBack() {
        val sniffer = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "x." + s.extensions.first())
            assertEquals("spec ${s.id}", s.id, r.spec?.id)
        }
    }

    @Test fun enoughSpecs() = assertTrue("only ${specs.size}", specs.size >= 160)

    @Test fun labelsAndExtensions() {
        for (s in specs) { assertTrue(s.id, s.label.isNotBlank()); assertTrue(s.id, s.extensions.isNotEmpty()) }
    }

    @Test fun containersExist() {
        val ids = specs.map { it.id }.toSet()
        for (s in specs) s.containerId?.let { assertTrue("${s.id} -> $it", it in ids) }
    }
}
