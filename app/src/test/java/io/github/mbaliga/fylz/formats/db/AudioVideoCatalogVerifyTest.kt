package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class AudioVideoCatalogVerifyTest {
    private val specs = AudioVideoCatalog.specs
    private val sniffer = FormatSniffer(specs)

    @Test fun sampleWithWrongExtensionSniffsToSelf() {
        val bad = mutableListOf<String>()
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "x.zzz")
            if (r.spec?.id != s.id) bad += "${s.id} -> ${r.spec?.id} alts=${r.alternatives.map { it.id }}"
        }
        assertTrue("wrong-ext misidentification: $bad", bad.isEmpty())
    }

    @Test fun sampleWithOwnExtensionSniffsToSelf() {
        val bad = mutableListOf<String>()
        for (s in specs) for (e in s.extensions) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "x.$e")
            if (r.spec?.id != s.id) bad += "${s.id}.$e -> ${r.spec?.id}"
        }
        assertTrue("own-ext misidentification: $bad", bad.isEmpty())
    }

    @Test fun noIdenticalSignaturesAcrossSpecs() {
        val seen = HashMap<String, String>(); val bad = mutableListOf<String>()
        for (s in specs) for (g in s.signatures) {
            val k = g.parts.joinToString("|") { "${it.offset}:${it.bytes.joinToString("") { b -> "%02x".format(b) }}" }
            val o = seen.put(k, s.id)
            if (o != null && o != s.id) bad += "$o/${s.id}"
        }
        assertTrue("identical signatures: $bad", bad.isEmpty())
    }

    @Test fun extensionsClaimedOnceOrPrimaryOnly() {
        val m = specs.flatMap { s -> s.extensions.map { it to s.id } }.groupBy({ it.first }, { it.second })
        val bad = m.filter { it.value.size > 1 }
        assertTrue("shared extensions: $bad", bad.isEmpty())
    }

    @Test fun primaryExtensionMatchesIdOrDocumented() {
        val bad = specs.filter { it.extensions.isNotEmpty() && it.id !in it.extensions }.map { it.id }
        assertEquals(listOf("psf-ps1", "ts-mpegts", "gpx-guitarpro"), bad.sorted().let { listOf("psf-ps1", "ts-mpegts", "gpx-guitarpro").filter { x -> x in it } })
        // la-lossless and amf-module were qualified during integration (la / amf belong to other formats)
        assertEquals(setOf("psf-ps1", "ts-mpegts", "gpx-guitarpro", "la-lossless", "amf-module"), bad.toSet())
    }

    @Test fun weakSignaturesAreFewBytes() {
        val weak = specs.flatMap { s -> s.signatures.filter { it.specificity < 4 }.map { s.id } }
        assertEquals(listOf("mp3"), weak)
    }

    @Test fun containerSpecsResolveFromContainerSample() {
        val bad = mutableListOf<String>()
        for (s in specs) {
            val c = s.containerId ?: continue
            val cs = specs.first { it.id == c }
            for (g in cs.signatures.take(1)) {
                val r = sniffer.sniff(g.sample(), "x.${s.extensions.first()}")
                if (r.spec?.id != s.id) bad += "${s.id} via $c -> ${r.spec?.id}"
            }
        }
        assertTrue("container resolve: $bad", bad.isEmpty())
    }

    @Test fun ssaFileNotMisreadAsAss() {
        val r = sniffer.sniff("[Script Info]\nTitle: x\n".toByteArray(), "movie.ssa")
        assertEquals("ssa", r.spec?.id)
    }

    @Test fun extensionOnlyFormatsResolveByName() {
        for (s in specs) for (e in s.extensions) {
            val r = sniffer.sniff(ByteArray(16), "a.$e")
            assertNotNull("$e", r.spec)
        }
    }
}
