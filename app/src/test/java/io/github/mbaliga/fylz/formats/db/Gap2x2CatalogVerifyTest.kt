package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap2x2CatalogVerifyTest {
    private val specs = Gap2x2Catalog.specs

    @Test fun wrongExtensionStillSniffsToOwnSpec() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sn.sniff(g.sample(), "wrong.bin").id)
    }
    @Test fun extensionsLowercaseUniqueWithinCatalog() {
        val all = specs.flatMap { it.extensions }
        assertTrue(all.all { it == it.lowercase() && !it.startsWith(".") })
        assertEquals(all.size, all.toSet().size)
    }
    @Test fun signaturesAtLeast4ConstrainedBytes() {
        for (s in specs) for (g in s.signatures)
            assertTrue(s.id, g.parts.sumOf { p -> p.bytes.indices.count { i -> p.mask == null || p.mask!![i].toInt() != 0 } } >= 4)
    }
    @Test fun idIsPrimaryExtension() {
        for (s in specs) assertEquals(s.id, s.extensions.first())
    }
    // Defect documentation: lwob/lwsc are not real file extensions (LightWave uses .lwo/.lws).
    @Test fun noInventedLightWaveExtensions() {
        assertFalse(specs.any { it.id == "lwob" })
        assertFalse(specs.flatMap { it.extensions }.any { it == "lwob" || it == "lwsc" })
    }
}
