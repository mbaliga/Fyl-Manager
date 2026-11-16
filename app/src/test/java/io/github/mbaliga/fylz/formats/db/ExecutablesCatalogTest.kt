package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class ExecutablesCatalogTest {
    private val specs = ExecutablesCatalog.specs

    @Test fun idsUnique() {
        val dup = specs.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate ids $dup", dup.isEmpty())
    }

    @Test fun enoughSpecs() = assertTrue("only ${specs.size}", specs.size >= 110)

    @Test fun labelsNotBlank() = specs.forEach { assertTrue(it.id, it.label.isNotBlank()) }

    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures)
            assertTrue(s.id, g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun signaturesSniffToOwner() {
        val sniffer = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "x." + s.extensions.first())
            assertEquals("spec ${s.id}", s.id, r.spec?.id)
        }
    }
}
