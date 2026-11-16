package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap2x6CatalogVerifyTest {
    private val specs = Gap2x6Catalog.specs
    private val sniffer = FormatSniffer(specs)

    @Test fun wrongExtensionStillSniffsToOwnSpec() {
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "file.txt")
            assertEquals(s.id, r.spec?.id)
        }
    }
    @Test fun idsAndExtensionsLowercaseNoDots() {
        for (s in specs) {
            assertEquals(s.id, s.id.lowercase())
            assertTrue(s.id, s.extensions.contains(s.id) || s.extensions.contains(s.id.substringBefore("-")))
            for (e in s.extensions) assertTrue(e, e == e.lowercase() && !e.startsWith("."))
        }
    }
    @Test fun noExtensionClaimedTwiceInside() {
        val all = specs.flatMap { it.extensions }
        assertEquals(all.groupBy { it }.filter { it.value.size > 1 }.keys.toString(), all.size, all.toSet().size)
    }
    @Test fun signaturesAtLeastFourConstrainedBytes() {
        for (s in specs) for (g in s.signatures) assertTrue(s.id, g.specificity >= 4)
    }
    @Test fun knownMagics() {
        fun has(id: String, hex: String) = specs.first { it.id == id }.signatures.any {
            it.sample().joinToString("") { b -> "%02X".format(b) }.startsWith(hex)
        }
        assertTrue(has("stx", "52535900")); assertTrue(has("nbz", "43154164"))
        assertTrue(has("pup", "5343455546")); assertTrue(has("ppf", "D6C3C400"))
    }
}
