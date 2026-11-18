package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class ArchivesCatalogTest {
    private val specs = ArchivesCatalog.specs

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
            assertEquals(s.id, sniffer.sniff(g.sample(), "x." + s.extensions.first()).spec?.id)
        }
    }

    @Test fun enoughSpecs() = assertTrue("count ${specs.size}", specs.size >= 130)

    @Test fun labelsNotBlank() = assertTrue(specs.all { it.label.isNotBlank() })

    @Test fun containersExist() {
        val ids = specs.map { it.id }.toSet()
        for (s in specs) s.containerId?.let { assertTrue("${s.id} -> $it", it in ids) }
    }
}
