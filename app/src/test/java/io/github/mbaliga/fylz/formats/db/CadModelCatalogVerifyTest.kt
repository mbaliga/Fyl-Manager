package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class CadModelCatalogVerifyTest {
    private val specs = CadModelCatalog.specs
    private val sn = FormatSniffer(specs)

    @Test fun sampleSniffsToOwnerWithWrongExtension() {
        val bad = mutableListOf<String>()
        for (s in specs) for (g in s.signatures) for (name in listOf("x.bin", "x.txt", null)) {
            val r = sn.sniff(g.sample(), name)
            if (r.spec?.id != s.id) bad += "${s.id}@$name->${r.spec?.id}"
        }
        assertTrue("wrong-ext misidentified: $bad", bad.isEmpty())
    }

    @Test fun signatureSpecificityAtLeastFour() {
        val bad = specs.filter { s -> s.signatures.any { it.specificity < 4 } }.map { it.id }
        assertTrue("weak sigs $bad", bad.isEmpty())
    }

    @Test fun noSharedSignaturesBetweenSpecs() {
        val seen = mutableMapOf<String, String>(); val dup = mutableListOf<String>()
        for (s in specs) for (g in s.signatures) {
            val k = g.parts.joinToString("|") { it.offset.toString() + ":" + it.bytes.joinToString(",") }
            val o = seen.put(k, s.id); if (o != null && o != s.id) dup += "$o/${s.id}"
        }
        assertTrue("shared sigs $dup", dup.isEmpty())
    }

    @Test fun containerIdsExistAndHaveSignatures() {
        for (s in specs) s.containerId?.let { c ->
            val t = specs.firstOrNull { it.id == c }
            assertNotNull("${s.id} container $c missing", t)
            assertTrue("${s.id} container $c has no sig", t!!.signatures.isNotEmpty())
        }
    }

    @Test fun noGenericMimeClaims() {
        val generic = setOf("application/octet-stream", "application/xml", "application/json", "text/plain", "application/zip")
        val bad = specs.filter { s -> s.mimeTypes.any { it in generic } }.map { it.id }
        assertTrue("generic mimes claimed by $bad", bad.isEmpty())
    }

    @Test fun idsAreFirstExtensionOrQualified() {
        for (s in specs) assertTrue(s.id, s.id == s.extensions.first() || s.id.startsWith(s.extensions.first() + "-") || s.id in s.extensions)
    }

    @Test fun noDuplicateExtensionInSpec() {
        for (s in specs) assertEquals(s.id, s.extensions.size, s.extensions.toSet().size)
    }

    @Test fun mimesWellFormed() {
        for (s in specs) for (m in s.mimeTypes) assertTrue("${s.id} $m", Regex("[a-z0-9.+-]+/[a-z0-9.+_-]+").matches(m))
    }

    @Test fun categoriesSane() {
        val ok = setOf(FormatCategory.CAD, FormatCategory.MODEL_3D, FormatCategory.EDA, FormatCategory.MANUFACTURING,
            FormatCategory.ENGINEERING, FormatCategory.GIS)
        assertTrue(specs.all { it.category in ok })
    }
}
