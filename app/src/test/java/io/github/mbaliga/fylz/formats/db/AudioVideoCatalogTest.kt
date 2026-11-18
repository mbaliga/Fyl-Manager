package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class AudioVideoCatalogTest {
    private val specs = AudioVideoCatalog.specs

    @Test fun idsUnique() {
        val dup = specs.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate ids $dup", dup.isEmpty())
    }

    @Test fun atLeast150() = assertTrue("only ${specs.size}", specs.size >= 150)

    @Test fun labelsNotBlank() = specs.forEach { assertTrue(it.id, it.label.isNotBlank()) }

    @Test fun signaturesWithinHeader() {
        for (s in specs) for (g in s.signatures)
            assertTrue("${s.id} ends at ${g.end}", g.end <= FormatSniffer.MAX_HEADER_BYTES)
    }

    @Test fun signaturesSniffToSelf() {
        val sniffer = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "x." + s.extensions.first())
            assertEquals("${s.id} -> ${r.spec?.id}", s.id, r.spec?.id)
        }
    }

    @Test fun containersExist() {
        val ids = specs.map { it.id }.toSet()
        for (s in specs) s.containerId?.let { assertTrue("${s.id} container $it", it in ids) }
    }
}
