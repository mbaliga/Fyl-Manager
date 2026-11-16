package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap1x1CatalogVerifyTest {
    private val specs = Gap1x1Catalog.specs

    @Test fun sampleSniffsToOwnSpecWithWrongExtension() {
        val sniffer = FormatSniffer(specs)
        for (s in specs) for (sg in s.signatures) {
            val r = sniffer.sniff(sg.sample(), "file.zzzwrong")
            assertEquals(s.id, r.spec?.id)
        }
    }
    @Test fun idsAreLowercasePrimaryExtension() {
        for (s in specs) {
            assertEquals(s.id, s.id.lowercase())
            assertEquals(s.id, s.extensions.first())
            assertTrue(s.extensions.all { it == it.lowercase() && !it.startsWith(".") })
        }
    }
    @Test fun noDuplicateExtensionsWithinCatalog() {
        val all = specs.flatMap { it.extensions }
        assertEquals(all.size, all.toSet().size)
    }
    @Test fun signaturesAreStrongEnough() {
        for (s in specs) for (sg in s.signatures) assertTrue(s.id, sg.sample().size >= 4 + 0)
    }
    @Test fun acbIsNotAfs2() {
        // real .acb files begin with "@UTF"; AFS2 is the .awb wave archive
        assertFalse(specs.first { it.id == "acb" }.signatures.any { it.sample().decodeToString(0, 4) == "AFS2" })
    }
}
