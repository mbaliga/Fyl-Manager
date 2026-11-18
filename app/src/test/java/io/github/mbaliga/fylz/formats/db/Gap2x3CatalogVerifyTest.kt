package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap2x3CatalogVerifyTest {
    private val specs = Gap2x3Catalog.specs

    @Test fun sampleSniffsWithWrongExtension() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            val r = sn.sniff(g.sample(), "file.zzz")
            assertEquals(s.id, r.spec?.id)
        }
    }

    @Test fun sampleSniffsWithOtherSpecExtension() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures) {
            val other = specs.first { it.id != s.id && it.signatures.isEmpty() }
            val r = sn.sniff(g.sample(), "file." + other.extensions.first())
            assertEquals("${s.id} vs ${other.id}", s.id, r.spec?.id)
        }
    }

    @Test fun extensionsLowercaseNoDotNoDupWithinSpec() {
        for (s in specs) {
            assertTrue(s.id, s.extensions.isNotEmpty())
            for (e in s.extensions) {
                assertEquals(s.id + ":" + e, e.lowercase().trimStart('.'), e)
                assertFalse(s.id, e.isBlank())
            }
            assertEquals(s.id, s.extensions.size, s.extensions.toSet().size)
        }
    }

    @Test fun extensionClaimedByOneSpec() {
        val m = specs.flatMap { s -> s.extensions.map { it to s.id } }.groupBy({ it.first }, { it.second })
        val dup = m.filter { it.value.distinct().size > 1 }
        assertTrue("shared extensions: $dup", dup.isEmpty())
    }

    @Test fun signaturesHaveEnoughConstrainedBytes() {
        for (s in specs) for (g in s.signatures) {
            val n = g.parts.sumOf { p -> p.bytes.indices.count { i -> p.mask == null || p.mask!![i].toInt() != 0 } }
            assertTrue("${s.id} only $n constrained bytes", n >= 4)
        }
    }

    @Test fun extensionOnlyLookup() {
        val sn = FormatSniffer(specs)
        for (s in specs) assertTrue(s.id, sn.forExtension(s.extensions.first()).any { it.id == s.id })
    }

    @Test fun randomBytesDoNotMatch() {
        val sn = FormatSniffer(specs)
        val rnd = java.util.Random(7)
        repeat(300) {
            val b = ByteArray(512); rnd.nextBytes(b)
            val r = sn.sniff(b, null)
            assertTrue(r.spec?.id, r.confidence != Confidence.MAGIC)
        }
    }

    @Test fun labelsAndCategories() {
        for (s in specs) {
            assertTrue(s.id, s.label.trim() == s.label && s.label.length in 2..60)
            assertTrue(s.id, s.category in listOf(FormatCategory.SCIENTIFIC, FormatCategory.ENGINEERING, FormatCategory.STATISTICAL))
        }
    }
}
