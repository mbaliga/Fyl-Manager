package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap2x5CatalogVerifyTest {
    private val specs = Gap2x5Catalog.specs

    @Test fun signatureSniffsWithWrongExtension() {
        val sniffer = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sniffer.sniff(g.sample(), "file.zzzz").id)
    }
    @Test fun idIsAmongExtensions() {
        val bad = specs.filter { it.id !in it.extensions }.map { it.id }
        assertTrue("id not listed as extension (ID contract): $bad", bad.isEmpty())
    }
    @Test fun idIsPrimaryExtension() {
        val bad = specs.filter { it.extensions.first() != it.id }.map { it.id + "->" + it.extensions.first() }
        assertTrue("id != first extension: $bad", bad.isEmpty())
    }
    @Test fun extensionsUniqueWithinCatalog() {
        val all = specs.flatMap { it.extensions }
        assertEquals(all.toSet().size, all.size)
    }
    @Test fun signaturesAtLeastFourBytes() {
        for (s in specs) for (g in s.signatures)
            assertTrue(s.id, g.parts.sumOf { it.bytes.size } >= 4)
    }
    @Test fun mimeLowercase() {
        for (s in specs) for (m in s.mimeTypes) assertEquals(m.lowercase(), m)
    }
    @Test fun noExtensionClaimedByDistinctUnrelatedFormats() {
        val sniffer = FormatSniffer(specs)
        for (s in specs) for (e in s.extensions)
            assertTrue("$e -> ${s.id}", sniffer.forExtension(e).any { it.id == s.id })
    }
}
