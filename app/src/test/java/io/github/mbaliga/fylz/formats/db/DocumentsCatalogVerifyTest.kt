package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatSniffer
import org.junit.Assert.*
import org.junit.Test

class DocumentsCatalogVerifyTest {
    private val specs = DocumentsCatalog.specs
    private val sniffer = FormatSniffer(specs)

    @Test fun sampleWithWrongExtensionStillMatchesAMagicSpec() {
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "file.zzzwrong")
            val chosen = r.spec
            assertNotNull("spec ${s.id} sample unrecognised", chosen)
            assertTrue("spec ${s.id}: result ${chosen!!.id} has no matching signature",
                chosen.signatures.any { it.matches(g.sample()) })
            assertTrue("${s.id} must be chosen or listed", chosen.id == s.id || r.alternatives.any { it.id == s.id })
        }
    }

    @Test fun uniqueSamplesResolveToOwnSpecWithoutExtension() {
        for (s in specs) for (g in s.signatures) {
            val sample = g.sample()
            val sharing = specs.filter { o -> o.signatures.any { it.matches(sample) && it.specificity >= g.specificity } }
            if (sharing.size == 1) assertEquals(s.id, sniffer.sniff(sample, null).spec?.id)
        }
    }

    @Test fun sharedSignatureGroupsAreDisambiguatedByExtension() {
        for (s in specs) for (g in s.signatures) {
            val sample = g.sample()
            for (e in s.extensions) {
                val r = sniffer.sniff(sample, "name.$e")
                assertEquals("spec ${s.id} ext $e", s.id, r.spec?.id)
            }
        }
    }

    @Test fun odfWithMimetypeBeatsPlainZipSpecs() {
        for (id in listOf("odt", "ods", "odp", "epub", "idml", "odg", "odb", "sxw")) {
            val sig = specs.first { it.id == id }.signatures.first()
            assertEquals(id, sniffer.sniff(sig.sample(), "x.docx").spec?.id)
        }
    }

    @Test fun idsAreLowercaseAndNoBlankMime() {
        for (s in specs) {
            assertEquals(s.id, s.id.lowercase())
            assertTrue(s.id, s.mimeTypes.all { it.contains('/') && it == it.trim() })
            assertTrue(s.id, s.extensions.isNotEmpty())
            assertEquals(s.id, s.extensions.distinct().size, s.extensions.size)
        }
    }

    @Test fun idIsAnExtensionUnlessDocumented() {
        val odd = specs.filter { it.id !in it.extensions }.map { it.id }
        assertEquals(listOf("mm-freemind"), odd)
    }

    @Test fun signaturesAreSpecificEnough() {
        for (s in specs) for (g in s.signatures) assertTrue("${s.id} spec=${g.specificity}", g.specificity >= 4)
    }

    @Test fun noExtensionClaimedByTwoSpecsUnlessSharedContainer() {
        val dup = specs.flatMap { s -> s.extensions.map { it to s.id } }.groupBy({ it.first }, { it.second }).filter { it.value.size > 1 }
        assertTrue("dup exts $dup", dup.isEmpty())
    }

    @Test fun specCountAtLeast160() = assertTrue(specs.size >= 160)
}
