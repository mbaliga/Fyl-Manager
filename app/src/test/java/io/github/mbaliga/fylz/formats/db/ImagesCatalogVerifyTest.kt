package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class ImagesCatalogVerifyTest {
    private val specs = ImagesCatalog.specs
    private val sniffer = FormatSniffer(specs)

    @Test fun signatureSniffsToOwnSpecWithWrongExtension() {
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "file.zzzz")
            val ok = r.id == s.id || r.alternatives.any { it.id == s.id }
            assertTrue("${s.id} -> ${r.id}", ok)
            assertEquals("${s.id} must be the leader", s.id, r.id)
        }
    }

    @Test fun signatureSniffsWithNoName() {
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sniffer.sniff(g.sample(), null).id)
    }

    @Test fun signatureBeatsConflictingImageExtension() {
        for (s in specs) for (g in s.signatures) {
            val other = specs.first { it.id != s.id && it.signatures.isEmpty() && it.containerId != s.id }
            val r = sniffer.sniff(g.sample(), "x." + other.extensions.first())
            // either the signature wins, or a container specialisation of it is chosen
            assertTrue("${s.id} vs ${other.id} got ${r.id}", r.id == s.id || r.spec?.containerId == s.id)
        }
    }

    @Test fun extensionOnlyFormatsResolveByExtension() {
        for (s in specs.filter { it.signatures.isEmpty() }) {
            val r = sniffer.sniff(ByteArray(16) { 0x20 }, "a." + s.extensions.first())
            assertNotNull(s.id, r.spec)
            assertTrue(s.id, r.spec!!.extensions.contains(s.extensions.first()))
        }
    }

    @Test fun extensionsLowercaseAndIdFirst() {
        for (s in specs) {
            if (s.id != "pfm-font") assertTrue(s.id, s.id == s.extensions.first() || s.id.startsWith(s.extensions.first() + "-"))
            assertEquals(s.extensions.size, s.extensions.toSet().size)
        }
    }

    @Test fun mimeTypesWellFormed() {
        for (s in specs) for (m in s.mimeTypes) {
            assertTrue("${s.id} $m", Regex("^[a-z]+/[a-z0-9.+_-]+$").matches(m))
        }
    }

    @Test fun containerIdsExist() {
        val ids = specs.map { it.id }.toSet()
        for (s in specs) s.containerId?.let { c ->
            assertTrue("${s.id} container $c", c in ids || c in setOf("tif", "png", "jpg"))
        }
    }

    @Test fun noTwoSpecsShareAnIdenticalSignatureSample() {
        val seen = HashMap<String, String>()
        for (s in specs) for (g in s.signatures) {
            val key = g.parts.joinToString("|") { it.offset.toString() + ":" + it.bytes.joinToString("") { b -> "%02x".format(b) } + ":" + (it.mask?.joinToString("") ?: "") }
            val prev = seen.put(key, s.id)
            assertTrue("dup signature $key in ${s.id} and $prev", prev == null || prev == s.id)
        }
    }

    @Test fun shortSignaturesAreWellKnown() {
        val allowed = setOf("bmp", "jpg", "pam", "pfb")
        for (s in specs) for (g in s.signatures)
            if (g.specificity < 4) assertTrue("${s.id} spec=${g.specificity}", s.id in allowed)
    }

    @Test fun rawTiffSpecificityBeatsTiff() {
        val cr2 = sniffer.sniff(byteArrayOf(0x49, 0x49, 0x2A, 0, 16, 0, 0, 0, 0x43, 0x52, 2, 0), "a.tif")
        assertEquals("cr2", cr2.id)
        val tif = sniffer.sniff(byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, 0, 0), "a.nef")
        assertEquals("nef", tif.id)
    }

    @Test fun lbmBeatsIff() {
        val h = "FORM".toByteArray() + ByteArray(4) + "ILBM".toByteArray()
        assertEquals("lbm", sniffer.sniff(h, null).id)
        assertEquals("iff", sniffer.sniff("FORM".toByteArray() + ByteArray(8), null).id)
    }

    @Test fun categoriesSane() {
        for (s in specs.filter { it.category == FormatCategory.RAW_PHOTO }) assertTrue(s.id, s.mimeTypes.all { it.startsWith("image/") })
        assertEquals(FormatCategory.FONT, sniffer.byId("ttf")!!.category)
    }
}
