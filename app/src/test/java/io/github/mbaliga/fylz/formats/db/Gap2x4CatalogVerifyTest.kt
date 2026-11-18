package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gap2x4CatalogVerifyTest {
    private val specs = Gap2x4Catalog.specs

    @Test fun signaturesSniffWithWrongExtension() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            assertEquals(s.id, sn.sniff(g.sample(), "file.zzz").id)
            assertEquals(s.id, sn.sniff(g.sample() + ByteArray(64), null).id)
        }
    }

    @Test fun noIdCollisionWithExistingCatalog() {
        val existing = setOf("edf", "bdf", "bdf-biosemi", "fif", "vhdr", "nii", "minc", "dcm")
        val dup = specs.map { it.id }.filter { it in existing }
        assertTrue("ids already defined elsewhere: $dup", dup.isEmpty())
    }

    @Test fun noExtensionCollisionWithExistingCatalog() {
        val existing = setOf("edf", "bdf", "fif", "vhdr", "mnc", "nii", "nii.gz")
        val dup = specs.flatMap { it.extensions }.filter { it in existing }
        assertTrue("extensions already claimed elsewhere: $dup", dup.isEmpty())
    }

    @Test fun idsAreLowercaseNoDot() {
        for (s in specs) assertTrue(s.id, s.id == s.id.lowercase() && !s.id.contains('.') && s.id.isNotBlank())
    }

    @Test fun primaryExtensionMatchesId() {
        for (s in specs) assertTrue(s.id + " not in " + s.extensions, s.id in s.extensions)
    }

    @Test fun extensionsLowercaseNoDotPrefix() {
        for (s in specs) for (e in s.extensions) assertTrue(e, e == e.lowercase() && !e.startsWith("."))
    }

    @Test fun signaturesAtLeastFourBytes() {
        for (s in specs) for (g in s.signatures) assertTrue(s.id, g.sample().size >= 4)
    }
}
