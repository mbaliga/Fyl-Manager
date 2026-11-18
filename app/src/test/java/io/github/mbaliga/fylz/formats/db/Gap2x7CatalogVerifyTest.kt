package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap2x7CatalogVerifyTest {
    private val specs = Gap2x7Catalog.specs

    @Test fun samplesSniffWithWrongExtension() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sn.sniff(g.sample(), "file.zzz").id)
    }

    @Test fun samplesSniffWithNoName() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) assertEquals(s.id, sn.sniff(g.sample()).id)
    }

    @Test fun idIsAnExtensionAndLowercase() {
        for (s in specs) assertTrue(s.id, s.id in s.extensions)
    }

    @Test fun noDuplicateExtensionsWithinCatalog() {
        val all = specs.flatMap { it.extensions }
        assertEquals(all.groupBy { it }.filter { it.value.size > 1 }.keys.toString(), all.size, all.toSet().size)
    }

    @Test fun allGameCategory() = assertTrue(specs.all { it.category == FormatCategory.GAME })

    /** Ids/extensions already owned by GamesNiche (rbxl, rbxm), Gap2x8 (btr = Btrieve) and Gap2x3 (gmx). */
    @Test fun noCollisionWithOtherCatalogs() {
        val taken = setOf("rbxl", "rbxm", "btr", "gmx")
        val clash = specs.flatMap { listOf(it.id) + it.extensions }.filter { it in taken }.toSet()
        assertTrue("collides with other catalogs: $clash", clash.isEmpty())
    }

    /** LCF files start with a length-prefixed string: the prefix byte must equal the string length. */
    @Test fun lcfLengthPrefixMatchesString() {
        val lmu = specs.first { it.id == "lmu" }
        for (g in lmu.signatures) {
            val s = g.sample()
            val len = s[0].toInt()
            val str = String(s, 1, s.size - 1, Charsets.ISO_8859_1)
            assertEquals("LCF header $str", str.length, len)
        }
    }

    @Test fun lcfSaveDataIs0B() {
        val lmu = specs.first { it.id == "lmu" }
        val save = lmu.signatures.first { String(it.sample(), 1, it.sample().size - 1, Charsets.ISO_8859_1) == "LcfSaveData" }
        assertEquals(0x0B, save.sample()[0].toInt())
    }

    @Test fun lcfMapTreeIs0A() {
        val lmu = specs.first { it.id == "lmu" }
        val t = lmu.signatures.first { String(it.sample(), 1, it.sample().size - 1, Charsets.ISO_8859_1) == "LcfMapTree" }
        assertEquals(0x0A, t.sample()[0].toInt())
    }

    @Test fun archiveContainersDeclared() {
        // ipk3 is a ZIP and pk7/ipk7 are 7z: the spec lumps them under a patch format with no containerId.
        val deh = specs.first { it.id == "deh" }
        assertFalse("ipk3/pk7 are zip/7z archives, not DeHackEd", deh.extensions.any { it in listOf("ipk3", "pk7", "ipk7") })
    }
}
