package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap2x1CatalogVerifyTest {
    private val specs = Gap2x1Catalog.specs
    private val sn = FormatSniffer(specs)

    @Test fun idLowercaseNoDot() {
        for (s in specs) assertTrue(s.id, s.id == s.id.lowercase() && !s.id.contains('.') && !s.id.contains(' '))
    }
    @Test fun idIsAnExtension() {
        for (s in specs) assertTrue("${s.id} not in its extensions", s.id in s.extensions || s.id.substringBefore("-") in s.extensions)
    }
    @Test fun extensionsLowercase() {
        for (s in specs) for (e in s.extensions) assertTrue("${s.id}:$e", e == e.lowercase() && !e.startsWith("."))
    }
    @Test fun noDuplicateExtensionAcrossSpecs() {
        val seen = HashMap<String, String>()
        for (s in specs) for (e in s.extensions) {
            val p = seen.put(e, s.id)
            assertTrue("ext $e used by ${s.id} and $p", p == null)
        }
    }
    @Test fun signaturesAtLeastFourConstrainedBytes() {
        for (s in specs) for (g in s.signatures) assertTrue("${s.id} sig too short", g.sample().size >= 4)
    }
    @Test fun uniqueSignaturePerSpec() {
        val seen = HashMap<String, String>()
        for (s in specs) for (g in s.signatures) {
            val k = g.sample().joinToString(",")
            val p = seen.put(k, s.id)
            assertTrue("${s.id} shares signature with $p", p == null || p == s.id)
        }
    }
    @Test fun signatureSniffsToSelfWithWrongExtension() {
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sn.sniff(g.sample(), "x.zzzwrong").id)
    }
    @Test fun signatureSniffsToSelfWithNoName() {
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sn.sniff(g.sample(), null).id)
    }
    @Test fun extensionOnlySpecsResolveByName() {
        for (s in specs) assertEquals(s.id, sn.sniff(ByteArray(16) { 0x5A }, "file." + s.extensions.first()).id)
    }
}
