package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class EmailCryptoSystemCatalogVerifyTest {
    private val specs = EmailCryptoSystemCatalog.specs
    private val sn = FormatSniffer(specs)

    @Test fun signatureSampleSniffsToOwnSpecWithWrongExtension() {
        val bad = mutableListOf<String>()
        for (s in specs) for (g in s.signatures) {
            val r = sn.sniff(g.sample(), "file.zzz")
            if (r.spec?.id != s.id) bad += "${s.id}->${r.spec?.id}"
            val r2 = sn.sniff(g.sample(), "file.txt")
            if (r2.spec?.id != s.id) bad += "${s.id}(txt)->${r2.spec?.id}"
        }
        assertTrue("misrouted: $bad", bad.isEmpty())
    }

    @Test fun signaturesHaveAtLeastFourConstrainedBytes() {
        val weak = specs.filter { s -> s.signatures.any { it.specificity < 4 } }.map { it.id }
        assertTrue("weak signatures: $weak", weak.isEmpty())
    }

    @Test fun containerIdsResolve() {
        for (s in specs) s.containerId?.let { assertNotNull("${s.id} -> $it", sn.byId(it)) }
    }

    @Test fun containerChildResolvesToChildOnContainerMagic() {
        for (s in specs.filter { it.containerId != null }) {
            val parent = sn.byId(s.containerId!!)!!
            val sample = parent.signatures.first().sample()
            val r = sn.sniff(sample, "f." + s.extensions.first())
            assertEquals(s.id, r.spec?.id)
        }
    }

    @Test fun extensionsUniqueWithinCatalog() {
        val dup = specs.flatMap { s -> s.extensions.map { it to s.id } }
            .groupBy({ it.first }, { it.second }).filter { it.value.distinct().size > 1 }
        assertTrue("shared extensions: $dup", dup.isEmpty())
    }

    @Test fun primaryExtensionMatchesIdExceptKnown() {
        val allowed = setOf("key-crypto", "thumbs-db", "vcf-vcard", "pub-ssh", "asc-pgp", "scf-explorer", "ips-crash") // qualified per the ID CONTRACT
        val bad = specs.filter { it.id !in allowed && it.id != it.extensions.first() }.map { it.id }
        assertTrue("id != primary ext: $bad", bad.isEmpty())
    }

    @Test fun mimeTypesWellFormedAndLowercase() {
        for (s in specs) for (m in s.mimeTypes)
            assertTrue("${s.id}: $m", m == m.lowercase() && Regex("[a-z0-9.+-]+/[a-z0-9.+-]+").matches(m))
    }

    @Test fun extensionOnlyLookupsWork() {
        for (s in specs) for (e in s.extensions) {
            val r = sn.sniff(ByteArray(0), "name.$e")
            assertNotNull("$e", r.spec)
        }
    }

    // Variants that share a container magic should resolve by extension, not be swallowed by the generic parent.
    @Test fun icsVariantsByExtension() {
        val h = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n".toByteArray()
        assertEquals("ifb", sn.sniff(h, "x.ifb").spec?.id)
        assertEquals("vcs", sn.sniff(h, "x.vcs").spec?.id)
    }

    @Test fun thunderbirdMsfNotMisreadAsMab() {
        val h = "// <!-- <mdb:mork:z v=\"1.4\"/> -->\n".toByteArray()
        assertEquals("msf", sn.sniff(h, "INBOX.msf").spec?.id)
    }

    @Test fun pemEncodedCertsKeepTheirExtension() {
        val h = "-----BEGIN CERTIFICATE-----\nMIIB\n".toByteArray()
        assertEquals("crt", sn.sniff(h, "server.crt").spec?.id)
        assertEquals("cer", sn.sniff(h, "server.cer").spec?.id)
    }

    @Test fun knownRealMagics() {
        fun id(b: ByteArray, n: String) = sn.sniff(b, n).spec?.id
        fun pad(vararg v: Int, at: Int = 0, len: Int = 80) = ByteArray(len).also { for (i in v.indices) it[at + i] = v[i].toByte() }
        // kdbx, evtx, vhd and qcow2 moved to the catalogs that own them (GamesNiche, DataConfig, Archives).
        assertEquals("pst", id("!BDN".toByteArray() + ByteArray(20), "a.bin"))
        assertEquals("dbx", id(pad(0xCF, 0xAD, 0x12, 0xFE, 0xC5, 0xFD, 0x74, 0x6F), "a.bin"))
        assertEquals("dbx", id(pad(0xCF, 0xAD, 0x12, 0xFE, 0xC6, 0xFD, 0x74, 0x6F), "a.bin"))
        assertEquals("jks", id(pad(0xFE, 0xED, 0xFE, 0xED), "a.bin"))
        assertEquals("hiv", id("regf".toByteArray() + ByteArray(20), "a.bin"))
    }

    @Test fun randomBytesDoNotMatch() {
        val rnd = java.util.Random(42)
        var hits = 0
        repeat(20000) { val b = ByteArray(256); rnd.nextBytes(b); if (sn.sniff(b, null).spec != null) hits++ }
        assertTrue("false positives on random data: $hits", hits == 0)
    }

    @Test fun thumbsDbAndWinmailDatByWholeName() {
        assertEquals("thumbs-db", sn.sniff(ByteArray(0), "Thumbs.db").spec?.id)
        assertEquals("tnef", sn.sniff(ByteArray(0), "winmail.dat").spec?.id)
        assertEquals("ds_store", sn.sniff(ByteArray(0), ".DS_Store").spec?.id)
    }
}
