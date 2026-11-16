package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class ExecutablesCatalogVerifyTest {
    private val specs = ExecutablesCatalog.specs
    private val sniffer = FormatSniffer(specs)

    private fun hdr(size: Int = 600, vararg set: Pair<Int, Int>): ByteArray {
        val b = ByteArray(size)
        for ((o, v) in set) b[o] = v.toByte()
        return b
    }
    private fun put(b: ByteArray, off: Int, vararg v: Int) { v.forEachIndexed { i, x -> b[off + i] = x.toByte() } }

    @Test fun samplesSniffWithWrongExtension() {
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "file.zzqq")
            assertEquals("spec ${s.id} with wrong ext", s.id, r.spec?.id)
        }
    }

    @Test fun samplesSniffWithMisleadingKnownExtension() {
        // other spec's extension must not hijack a magic hit unless it is a container specialisation
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample(), "file.txt")
            assertEquals("spec ${s.id} with .txt", s.id, r.spec?.id)
        }
    }

    @Test fun realAppImageHeader() {
        val b = hdr(64); put(b, 0, 0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0); put(b, 8, 0x41, 0x49, 0x02)
        assertEquals("appimage", sniffer.sniff(b, "x.bin").spec?.id)
    }

    @Test fun realElfCoreHeader() {
        val b = hdr(64); put(b, 0, 0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0); put(b, 16, 4, 0)
        assertEquals("core", sniffer.sniff(b, "x.bin").spec?.id)
    }

    @Test fun plainElfIsElf() {
        val b = hdr(64); put(b, 0, 0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0); put(b, 16, 3, 0)
        assertEquals("elf", sniffer.sniff(b, "x.bin").spec?.id)
        assertEquals("so", sniffer.sniff(b, "libx.so").spec?.id)
    }

    @Test fun javaClassVsFatMachO() {
        val c = hdr(16); put(c, 0, 0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 52)
        assertEquals("class", sniffer.sniff(c, "A.class").spec?.id)
        val f = hdr(16); put(f, 0, 0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 2)
        assertEquals("macho-fat", sniffer.sniff(f, "a.out").spec?.id)
    }

    @Test fun pycAllVersions() {
        for (v in listOf(0x33, 0x42, 0x55, 0x61, 0x6F, 0xA7, 0xCB, 0xF3)) {
            val b = hdr(16); put(b, 0, v, 0x0D, 0x0D, 0x0A)
            assertEquals("pyc %02X".format(v), "pyc", sniffer.sniff(b, "x.zz").spec?.id)
        }
    }

    @Test fun containerIdsExistAndHaveSignatures() {
        // containers may live in another catalog (ar is defined in ArchivesCatalog)
        val pool = specs + io.github.mbaliga.fylz.formats.db.ArchivesCatalog.specs
        val ids = pool.map { it.id }.toSet()
        for (s in specs) s.containerId?.let {
            assertTrue("${s.id} container $it missing", it in ids)
            assertTrue("${s.id} container $it has no signature", pool.first { x -> x.id == it }.signatures.isNotEmpty())
        }
    }

    @Test fun idIsPrimaryExtension() {
        val bad = specs.filter { s -> s.extensions.first().let { e -> s.id != e && !s.id.startsWith("$e-") } }.map { it.id }
        assertTrue("id != first extension: $bad", bad.isEmpty())
    }

    @Test fun shortSignaturesAreWellKnown() {
        val ok = setOf("exe", "swf")
        for (s in specs) for (g in s.signatures)
            if (g.specificity < 4) assertTrue("${s.id} weak sig ${g.specificity}", s.id in ok)
    }

    @Test fun canonicalIds() {
        for (id in listOf("exe", "elf", "macho")) assertNotNull(id, sniffer.byId(id))
    }
}
