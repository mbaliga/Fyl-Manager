package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataConfigCatalogVerifyTest {
    private val specs = DataConfigCatalog.specs
    private val sniffer = FormatSniffer(specs)

    @Test fun signatureSniffsToOwnSpecWithWrongExtension() {
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "file.zzzunknown")
            assertEquals("sample of ${s.id} with unknown ext", s.id, r.spec?.id)
            val r2 = sniffer.sniff(g.sample(), "file.csv")
            assertEquals("sample of ${s.id} with .csv ext", s.id, r2.spec?.id)
            assertTrue("mismatch flag ${s.id}", r2.extensionMismatch)
        }
    }

    @Test fun signatureSniffsWithoutName() {
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sniffer.sniff(g.sample(), null).spec?.id)
    }

    @Test fun signaturesHaveAtLeastFourConstrainedBytes() {
        for (s in specs) for (g in s.signatures)
            assertTrue("${s.id} weak signature ${g.specificity}", g.specificity >= 4)
    }

    @Test fun idIsPrimaryExtensionUnlessQualified() {
        for (s in specs) assertTrue("${s.id} not in ${s.extensions}",
            s.id in s.extensions || s.id.contains('-') || s.extensions.isNotEmpty())
        for (s in specs) if (!s.id.contains('-')) assertEquals(s.id, s.extensions.first())
    }

    @Test fun noExtensionClaimedByTwoSpecs() {
        val dup = specs.flatMap { s -> s.extensions.map { it to s.id } }
            .groupBy({ it.first }, { it.second }).filter { it.value.size > 1 }
        assertTrue("shared extensions: $dup", dup.isEmpty())
    }

    @Test fun mimeTypesWellFormed() {
        val re = Regex("^[a-z]+/[a-z0-9][a-z0-9.+_-]*$")
        for (s in specs) for (m in s.mimeTypes) assertTrue("${s.id} mime $m", re.matches(m))
    }

    @Test fun textSpecsHaveNoBinarySignatureAndContainerSpecsAreText() {
        for (s in specs) {
            if (s.containerId != null) assertTrue("${s.id} container spec not text", s.text)
            for (g in s.signatures) if (s.text) assertTrue("${s.id} text sig must be ascii",
                g.sample().all { it in 0x09..0x7E })
        }
    }

    @Test fun containerSpecsSniffViaExtension() {
        val xml = "<?xml version=\"1.0\"?><a/>".toByteArray()
        for (s in specs.filter { it.containerId == "xml" }) {
            val r = sniffer.sniff(xml, "f.${s.extensions.first()}")
            assertEquals(s.id, r.spec?.id)
        }
    }

    @Test fun categoriesAreSensible() {
        for (s in specs.filter { it.text }) assertTrue(s.id, s.category.name !in setOf("EXECUTABLE", "IMAGE", "AUDIO", "VIDEO"))
    }
}
