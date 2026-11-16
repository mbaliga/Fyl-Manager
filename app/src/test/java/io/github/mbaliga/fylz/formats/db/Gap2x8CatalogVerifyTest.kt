package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap2x8CatalogVerifyTest {
    private val specs = Gap2x8Catalog.specs

    @Test fun idIsPrimaryExtension() {
        val bad = specs.filter { it.id !in it.extensions }.map { it.id }
        assertTrue("id not among extensions: $bad", bad.isEmpty())
    }
    @Test fun extensionsLowercaseNoDots() {
        for (s in specs) for (e in s.extensions) assertTrue(s.id + " " + e, e == e.lowercase() && !e.startsWith("."))
    }
    @Test fun noExtensionCollisionsInsideCatalog() {
        val all = specs.flatMap { s -> s.extensions.map { it to s.id } }
        val dup = all.groupBy { it.first }.filter { it.value.size > 1 }
        assertTrue(dup.toString(), dup.isEmpty())
    }
    @Test fun jetSignatureSniffsWithWrongExtension() {
        for (s in specs) for (g in s.signatures) {
            val sn = FormatSniffer(specs)
            assertEquals(s.id, sn.sniff(g.sample(), "wrong.txt").id)
        }
    }
    @Test fun mdbNotStolenByMdw() {
        val jet = sigHex(0, "00 01 00 00") + sig(4, "Standard Jet DB")
        val mdb = FormatSpec("mdb", "Access", FormatCategory.DATABASE, listOf("mdb"), signatures = listOf(jet))
        val sn = FormatSniffer(listOf(mdb) + specs)
        assertEquals("mdb", sn.sniff(jet.sample(), "noext.dat").id)
        assertEquals("mdb", sn.sniff(jet.sample(), null).id)
    }
    @Test fun odftIsZipContainer() {
        assertEquals("zip", specs.first { it.id == "odft" }.containerId)
    }
}
