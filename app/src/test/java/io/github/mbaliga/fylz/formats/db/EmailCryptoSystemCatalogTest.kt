package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class EmailCryptoSystemCatalogTest {
    private val specs = EmailCryptoSystemCatalog.specs

    @Test fun idsUnique() {
        val dup = specs.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate ids: $dup", dup.isEmpty())
    }

    @Test fun enoughSpecs() = assertTrue("only ${specs.size}", specs.size >= 140)

    @Test fun labelsNotBlank() = specs.forEach { assertTrue(it.id, it.label.isNotBlank()) }

    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures)
            assertTrue(s.id, g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun signaturesSniffBack() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            val r = sn.sniff(g.sample(), "x." + s.extensions.first())
            assertEquals(s.id, r.spec?.id)
        }
    }
}
