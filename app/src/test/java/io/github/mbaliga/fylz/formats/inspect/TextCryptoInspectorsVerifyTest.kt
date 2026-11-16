package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import java.util.zip.CRC32

/** Adversarial verification: fuzz every inspector with counting sources and time limits. */
class TextCryptoInspectorsVerifyTest {
    // ---------- counting source ----------
    private class Counting(val data: ByteArray, val claimed: Long = data.size.toLong()) : ByteSource {
        var requested = 0L; var calls = 0
        override val size: Long get() = claimed
        override fun read(offset: Long, length: Int): ByteArray {
            calls++
            if (length > 0) requested += length
            if (offset < 0 || offset >= claimed || length <= 0) return ByteArray(0)
            val n = minOf(length.toLong(), claimed - offset).toInt()
            val out = ByteArray(n)
            if (offset < data.size) { val e = minOf(data.size.toLong(), offset + n).toInt(); System.arraycopy(data, offset.toInt(), out, 0, e - offset.toInt()) }
            return out
        }
    }

    private class Fx(val name: String, val insp: FormatInspector, val file: String?, val data: ByteArray)

    private val failures = ArrayList<String>()
    private var runs = 0
    private var slowest = 0L

    private fun run(fx: Fx, label: String, data: ByteArray, claimed: Long = data.size.toLong(), file: String? = fx.file) {
        val s = Counting(data, claimed)
        val t0 = System.nanoTime()
        runs++
        try {
            fx.insp.inspect(s, file)
        } catch (t: Throwable) {
            if (failures.size < 60) failures.add("${fx.name} [$label] threw $t")
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms > slowest) slowest = ms
        if (ms > 2000 && failures.size < 60) failures.add("${fx.name} [$label] took $ms ms")
        if (s.requested > FormatInspector.MAX_READ_BYTES && failures.size < 60) failures.add("${fx.name} [$label] requested ${s.requested} bytes")
        if (s.calls > 20000 && failures.size < 60) failures.add("${fx.name} [$label] made ${s.calls} read calls")
    }

    // ---------- builders (copied from the builder tests) ----------
    private fun concat(vararg p: ByteArray): ByteArray { val o = ByteArrayOutputStream(); p.forEach { o.write(it) }; return o.toByteArray() }
    private fun u32be(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun u32le(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun u16le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun u64le(v: Long) = u32le(v and 0xFFFFFFFFL) + u32le(v ushr 32)
    private fun filetime(unix: Long) = (unix + 11644473600L) * 10_000_000L
    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val len = body.size
        val l = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        }
        return concat(byteArrayOf(tag.toByte()), l, body)
    }
    private fun seq(vararg p: ByteArray) = tlv(0x30, concat(*p))
    private fun set(vararg p: ByteArray) = tlv(0x31, concat(*p))
    private fun oid(s: String): ByteArray {
        val a = s.split('.').map { it.toLong() }
        val o = ByteArrayOutputStream()
        o.write((a[0] * 40 + a[1]).toInt())
        for (v in a.drop(2)) {
            val parts = ArrayList<Int>()
            var x = v
            do { parts.add((x and 0x7F).toInt()); x = x shr 7 } while (x > 0)
            for (k in parts.indices.reversed()) o.write(parts[k] or (if (k > 0) 0x80 else 0))
        }
        return tlv(6, o.toByteArray())
    }
    private fun rdn(o: String, v: String) = set(seq(oid(o), tlv(12, v.toByteArray())))
    private fun name(cn: String, org: String? = null) =
        seq(*listOfNotNull(org?.let { rdn("2.5.4.10", it) }, rdn("2.5.4.3", cn)).toTypedArray())
    private val modulus = ByteArray(257).also { it[0] = 0; for (i in 1..256) it[i] = (0xC0 + i % 16).toByte() }
    private fun rsaSpki() = seq(seq(oid("1.2.840.113549.1.1.1"), tlv(5, ByteArray(0))),
        tlv(3, concat(byteArrayOf(0), seq(tlv(2, modulus), tlv(2, byteArrayOf(1, 0, 1))))))
    private fun ecSpki() = seq(seq(oid("1.2.840.10045.2.1"), oid("1.2.840.10045.3.1.7")), tlv(3, ByteArray(66) { 4 }))
    private fun certificate(spki: ByteArray = rsaSpki()): ByteArray {
        val san = seq(tlv(0x82, "example.test".toByteArray()), tlv(0x82, "www.example.test".toByteArray()), tlv(0x87, byteArrayOf(10, 0, 0, 1)), tlv(0x87, ByteArray(16) { it.toByte() }))
        val exts = tlv(0xA3, seq(
            seq(oid("2.5.29.19"), tlv(1, byteArrayOf(0xFF.toByte())), tlv(4, seq(tlv(1, byteArrayOf(0xFF.toByte()))))),
            seq(oid("2.5.29.17"), tlv(4, san)),
            seq(oid("2.5.29.15"), tlv(4, tlv(3, byteArrayOf(1, 0x06)))),
        ))
        val sigAlg = seq(oid("1.2.840.113549.1.1.11"), tlv(5, ByteArray(0)))
        val tbs = seq(
            tlv(0xA0, tlv(2, byteArrayOf(2))), tlv(2, byteArrayOf(0x0A, 0xBC.toByte())), sigAlg,
            name("Test CA"),
            seq(tlv(0x17, "240102030405Z".toByteArray()), tlv(0x18, "20340102030405Z".toByteArray())),
            name("example.test", "Fylz Org"), spki, exts,
        )
        return seq(tbs, sigAlg, tlv(3, concat(byteArrayOf(0), ByteArray(32) { 7 })))
    }
    private fun pem(label: String, der: ByteArray, headers: String = ""): String =
        "-----BEGIN $label-----\n$headers" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $label-----\n"

    private fun eml(): ByteArray {
        val from = "=?UTF-8?B?" + Base64.getEncoder().encodeToString("J\u00fcrgen".toByteArray()) + "?= <j@example.test>"
        return listOf(
            "From: $from", "To: Bob <bob@example.test>", "Subject: =?UTF-8?Q?Caf=C3=A9_menu?=",
            "Date: Mon, 1 Jan 2024 10:00:00 +0000", "Message-ID: <abc@example.test>", "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"BOUND\"", "",
            "--BOUND", "Content-Type: text/plain; charset=utf-8", "Content-Transfer-Encoding: quoted-printable", "",
            "Hello caf=C3=A9", "--BOUND", "Content-Type: application/pdf; name*0*=utf-8''rep; name*1*=ort.pdf",
            "Content-Disposition: attachment; filename=\"report.pdf\"", "Content-Transfer-Encoding: base64", "",
            "JVBERi0xLjQK", "--BOUND--", "",
        ).joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
    }
    private val mbox = ("From alice@example.test Mon Jan  1 10:00:00 2024\nFrom: alice@example.test\nSubject: First\n\nbody\n\n" +
        "From bob@example.test Tue Jan  2 10:00:00 2024\nFrom: bob@example.test\nSubject: Second\n\nhi\n").toByteArray()
    private val vcf = ("BEGIN:VCARD\r\nVERSION:3.0\r\nN:Doe;Jane;;;\r\nFN:Jane Doe\r\nORG:Acme\\, Inc.;Research\r\nEMAIL:jane@example.test\r\n" +
        "TEL;TYPE=CELL:+1 555 0100\r\nEND:VCARD\r\nBEGIN:VCARD\r\nVERSION:3.0\r\nFN:John\r\n  Smith\r\nEND:VCARD\r\n").toByteArray()
    private val ics = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Fylz//Test//EN", "X-WR-CALNAME:Team",
        "BEGIN:VTIMEZONE", "TZID:Europe/Paris", "BEGIN:STANDARD", "DTSTART:19701025T030000", "END:STANDARD", "END:VTIMEZONE",
        "BEGIN:VEVENT", "SUMMARY:Kickoff\\, big", "DTSTART;TZID=Europe/Paris:20240301T090000",
        "DTEND;TZID=Europe/Paris:20240301T100000", "RRULE:FREQ=WEEKLY", "BEGIN:VALARM", "SUMMARY:ignored", "END:VALARM", "END:VEVENT",
        "BEGIN:VEVENT", "SUMMARY:Holiday", "DTSTART;VALUE=DATE:20240501", "DTEND;VALUE=DATE:20240502", "END:VEVENT",
        "END:VCALENDAR", "",
    ).joinToString("\r\n").toByteArray()
    private fun pgpKeyPacket(): ByteArray {
        val n = ByteArray(256) { (0x80 or (it and 0x7F)).toByte() }
        val body = concat(byteArrayOf(4), u32be(1600000000L), byteArrayOf(1), byteArrayOf(0x08, 0x00), n, byteArrayOf(0x00, 0x11, 1, 0, 1))
        return concat(byteArrayOf(0xC6.toByte()), byteArrayOf(0xFF.toByte()), u32be(body.size.toLong()), body)
    }
    private fun pgpBytes(): ByteArray {
        val uid = "Alice <a@x.test>".toByteArray()
        val sig = concat(byteArrayOf(4, 0x13, 1, 8), ByteArray(20))
        return concat(pgpKeyPacket(), byteArrayOf(0xCD.toByte(), uid.size.toByte()), uid, byteArrayOf(0x88.toByte(), sig.size.toByte()), sig)
    }
    private fun pgpArmor() = ("-----BEGIN PGP PUBLIC KEY BLOCK-----\nVersion: Test 1.0\n\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pgpBytes()) + "\n=ABCD\n-----END PGP PUBLIC KEY BLOCK-----\n").toByteArray()
    private fun sstr(b: ByteArray) = concat(u32be(b.size.toLong()), b)
    private fun sstr(s: String) = sstr(s.toByteArray())
    private val edPub = ByteArray(32) { (it + 1).toByte() }
    private fun edBlob() = concat(sstr("ssh-ed25519"), sstr(edPub))
    private fun sshPubLine() = ("ssh-ed25519 " + Base64.getEncoder().encodeToString(edBlob()) + " me@box\n").toByteArray()
    private fun sshPrivPem(): ByteArray {
        val priv = concat(u32be(0xDEADBEEFL), u32be(0xDEADBEEFL), sstr("ssh-ed25519"), sstr(edPub), sstr(ByteArray(64)), sstr("me@box"), byteArrayOf(1, 2, 3))
        val file = concat("openssh-key-v1\u0000".toByteArray(), sstr("none"), sstr("none"), sstr(""), u32be(1), sstr(edBlob()), sstr(priv))
        return pem("OPENSSH PRIVATE KEY", file).toByteArray()
    }
    private fun utf16(s: String) = concat(u16le(s.length), s.toByteArray(Charsets.UTF_16LE))
    private fun lnk(): ByteArray {
        val clsid = intArrayOf(0x01, 0x14, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46)
        val ft = filetime(1600000000L)
        val flags = 2L or 4L or 8L or 16L or 32L or 0x80L
        val header = concat(u32le(0x4C), ByteArray(16) { clsid[it].toByte() }, u32le(flags), u32le(0x20),
            u64le(ft), u64le(ft + 10_000_000L * 60), u64le(ft + 10_000_000L * 3600), u32le(1536), u32le(0), u32le(1), u16le(0), ByteArray(10))
        val vol = concat(u32le(17), u32le(3), u32le(0x12345678), u32le(0x10), byteArrayOf(0))
        val basePath = "C:\\Windows\\notepad.exe\u0000".toByteArray(Charsets.ISO_8859_1)
        val suffix = byteArrayOf(0)
        val hdrSize = 0x1C
        val volOff = hdrSize
        val baseOff = volOff + vol.size
        val sufOff = baseOff + basePath.size
        val total = sufOff + suffix.size
        val li = concat(u32le(total.toLong()), u32le(hdrSize.toLong()), u32le(1), u32le(volOff.toLong()), u32le(baseOff.toLong()), u32le(0), u32le(sufOff.toLong()), vol, basePath, suffix)
        return concat(header, li, utf16("Notepad"), utf16("..\\notepad.exe"), utf16("C:\\Windows"), utf16("/p readme.txt"))
    }
    private fun regf(): ByteArray {
        val b = ByteArray(4096 + 32)
        "regf".toByteArray().copyInto(b, 0)
        u32le(7).copyInto(b, 4); u32le(7).copyInto(b, 8)
        u64le(filetime(1600000000L)).copyInto(b, 12)
        u32le(1).copyInto(b, 0x14); u32le(5).copyInto(b, 0x18)
        u32le(0x20).copyInto(b, 0x24); u32le(4096).copyInto(b, 0x28)
        "\\??\\C:\\Config\\SOFTWARE".toByteArray(Charsets.UTF_16LE).copyInto(b, 0x30)
        var x = 0L
        for (i in 0 until 127) x = x xor Bytes.u32le(b, i * 4)
        u32le(x).copyInto(b, 0x1FC)
        "hbin".toByteArray().copyInto(b, 4096)
        return b
    }
    private fun evtx(chunks: Int = 3): ByteArray {
        val b = ByteArray(4096)
        "ElfFile\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(b, 0)
        u64le(0).copyInto(b, 8); u64le(2).copyInto(b, 16); u64le(100).copyInto(b, 24)
        u32le(128).copyInto(b, 32); u16le(1).copyInto(b, 36); u16le(3).copyInto(b, 38)
        u16le(4096).copyInto(b, 40); u16le(chunks).copyInto(b, 42)
        val crc = CRC32(); crc.update(b, 0, 120)
        u32le(crc.value).copyInto(b, 124)
        return b
    }
    private fun bs(s: String) = "${s.length}:$s"
    private val infoSingle = "d6:lengthi12345e4:name8:file.bin12:piece lengthi16384e6:pieces20:${"A".repeat(20)}7:privatei1ee"
    private fun torrent(info: String = infoSingle) =
        ("d8:announce${bs("http://t.example/announce")}13:announce-listll${bs("http://t.example/announce")}el${bs("udp://u.example:80")}ee" +
            "13:creation datei1600000000e10:created by${bs("Fylz")}4:info${info}e").toByteArray(Charsets.ISO_8859_1)
    private val multiInfo = "d5:filesld6:lengthi100e4:pathl1:a5:b.txteed6:lengthi50e4:pathl5:c.txteee4:name4:root12:piece lengthi16384e6:pieces20:${"A".repeat(20)}e"
    private val v2Info = "d9:file treed1:ad0:d6:lengthi5e11:pieces root32:${"B".repeat(32)}eee12:meta versioni2e4:name1:x12:piece lengthi16384ee"

    private fun fixtures(): List<Fx> = listOf(
        Fx("text", TextFileInspector, "a.txt", "#!/usr/bin/env python3\r\n\tfoo bar\n  # vim: set ft=lua:\ncaf\u00e9\n".toByteArray()),
        Fx("text16", TextFileInspector, "a.txt", concat(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), "Hi there\nline\n".toByteArray(Charsets.UTF_16LE))),
        Fx("text32", TextFileInspector, "a.txt", concat(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0), "Hi\n".toByteArray(Charsets.UTF_16LE).let { b -> ByteArray(b.size * 2).also { o -> for (i in 0 until b.size step 2) { o[i * 2] = b[i]; o[i * 2 + 1] = b[i + 1] } } })),
        Fx("eml", EmlInspector, "m.eml", eml()),
        Fx("mbox", MboxInspector, "x.mbox", mbox),
        Fx("vcf", VcfInspector, "c.vcf", vcf),
        Fx("ics", IcsInspector, "c.ics", ics),
        Fx("pem-cert", PemInspector, "a.pem", (pem("CERTIFICATE", certificate()) + pem("RSA PRIVATE KEY", seq(tlv(2, byteArrayOf(0)), tlv(2, modulus))) +
            pem("PUBLIC KEY", ecSpki()) + pem("EC PRIVATE KEY", seq(tlv(2, byteArrayOf(1)), tlv(4, ByteArray(32)), tlv(0xA0, oid("1.3.132.0.34"))))).toByteArray()),
        Fx("pem-ssh", PemInspector, "id", sshPrivPem()),
        Fx("pem-csr", PemInspector, "a.csr", pem("CERTIFICATE REQUEST", seq(seq(tlv(2, byteArrayOf(0)), name("x"), rsaSpki()), seq(oid("1.2.840.113549.1.1.11")), tlv(3, ByteArray(10)))).toByteArray()),
        Fx("der-cert", DerInspector, "site.crt", certificate()),
        Fx("der-ec", DerInspector, "site.cer", certificate(ecSpki())),
        Fx("der-pem", DerInspector, "site.crt", pem("CERTIFICATE", certificate()).toByteArray()),
        Fx("p12", DerInspector, "k.p12", seq(tlv(2, byteArrayOf(3)), seq(oid("1.2.840.113549.1.7.1"), tlv(0xA0, tlv(4, ByteArray(10)))), seq(seq(oid("1.3.14.3.2.26")), tlv(4, ByteArray(20))))),
        Fx("der-spki", DerInspector, "k.der", rsaSpki()),
        Fx("pgp-armor", PgpInspector, "k.asc", pgpArmor()),
        Fx("pgp-bin", PgpInspector, "k.gpg", pgpBytes()),
        Fx("ssh-pub", SshPublicKeyInspector, "id.pub", sshPubLine()),
        Fx("lnk", LnkInspector, "n.lnk", lnk()),
        Fx("url", ShortcutInspector, "a.url", "[InternetShortcut]\r\nURL=https://example.test/a?b=1\r\nIconIndex=0\r\n".toByteArray()),
        Fx("webloc", ShortcutInspector, "a.webloc", "<?xml version=\"1.0\"?>\n<plist version=\"1.0\"><dict><key>URL</key>\n<string>https://e.test/?a=1&amp;b=2</string></dict></plist>".toByteArray()),
        Fx("desktop", ShortcutInspector, "a.desktop", "[Desktop Entry]\nType=Application\nName=Editor\nExec=editor %F\n\n[Desktop Action new]\nName=New\n".toByteArray()),
        Fx("regf", RegistryHiveInspector, "SOFTWARE.hiv", regf()),
        Fx("evtx", EvtxInspector, "Security.evtx", evtx()),
        Fx("torrent", TorrentInspector, "x.torrent", torrent()),
        Fx("torrent-multi", TorrentInspector, "x.torrent", torrent(multiInfo)),
        Fx("torrent-v2", TorrentInspector, "x.torrent", torrent(v2Info)),
    )

    private fun positions(size: Int, rnd: Random, cap: Int): List<Int> {
        if (size <= cap) return (0 until size).toList()
        val s = LinkedHashSet<Int>()
        for (i in 0 until minOf(size, 300)) s.add(i)
        while (s.size < cap) s.add(rnd.nextInt(size))
        return s.toList()
    }

    @Test fun fuzzAll() {
        val rnd = Random(12345)
        for (fx in fixtures()) {
            val d = fx.data
            // 1 prefixes
            val step = if (d.size > 3000) 7 else 1
            var n = 0
            while (n <= d.size) { run(fx, "prefix $n", d.copyOf(n)); n += step }
            run(fx, "full", d)
            // 2 byte flips
            for (p in positions(d.size, rnd, 1200)) for (v in intArrayOf(0xFF, 0x00, 0x80, 0x7F)) {
                val c = d.copyOf(); c[p] = v.toByte(); run(fx, "flip@$p=$v", c)
            }
            for (p in positions(d.size, rnd, 400)) { val c = d.copyOf(); c[p] = (c[p].toInt() xor 0x55).toByte(); run(fx, "xor@$p", c) }
            // 3 field overwrite
            val pats = listOf(byteArrayOf(-1, -1, -1, -1), byteArrayOf(0x7F, -1, -1, -1), byteArrayOf(-1, -1, -1, 0x7F), byteArrayOf(0, 0, 0, 0), byteArrayOf(-1, -1), byteArrayOf(0x7F, -1), byteArrayOf(-1, 0x7F))
            for (p in positions(d.size, rnd, 1500)) for ((pi, pat) in pats.withIndex()) {
                if (p + pat.size > d.size) continue
                val c = d.copyOf(); pat.copyInto(c, p); run(fx, "field@$p pat$pi", c)
            }
            // 4 zero / FF buffers
            for (sz in intArrayOf(0, 1, 2, 3, 4, 5, 8, 16, 31, 64, 76, 127, 128, 255, 512, 1000, 4096, 4128, 8192, 65536, 300_000)) {
                run(fx, "zeros $sz", ByteArray(sz)); run(fx, "ff $sz", ByteArray(sz) { -1 })
            }
            // 5 magic + random
            val magic = d.copyOf(minOf(d.size, 8))
            for (k in 0 until 150) {
                val r = ByteArray(rnd.nextInt(3000)); rnd.nextBytes(r)
                run(fx, "magic+rand $k", concat(magic, r))
                if (k % 5 == 0) run(fx, "rand $k", r)
            }
            // 6 claimed huge
            run(fx, "claimed 50GB", d, 50L * 1024 * 1024 * 1024)
            // 7 cross-feed: every fixture of every other inspector
            for (o in fixtures()) if (o.insp !== fx.insp) run(fx, "cross ${o.name}", o.data)
        }
        System.err.println("verify fuzz: $runs runs, slowest $slowest ms, ${failures.size} failures")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ---------- targeted hostile inputs ----------
    private fun timed(name: String, insp: FormatInspector, file: String?, data: ByteArray, out: MutableList<String>) {
        val s = Counting(data)
        val t0 = System.nanoTime()
        try { insp.inspect(s, file) } catch (t: Throwable) { out.add("$name threw $t") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        System.err.println("hostile $name: $ms ms, requested ${s.requested}, calls ${s.calls}")
        if (ms > 2000) out.add("$name took $ms ms")
        if (s.requested > FormatInspector.MAX_READ_BYTES) out.add("$name requested ${s.requested}")
    }

    @Test fun hostileShapes() {
        val out = ArrayList<String>()
        // header-less lines with no colon, followed by a colon at the very end
        val noColon = ("x\n".repeat(400_000) + "a: b\n").toByteArray()
        timed("eml no-colon lines", EmlInspector, "a.eml", noColon, out)
        timed("mbox no-colon lines", MboxInspector, "a.mbox", "From a\n".toByteArray() + noColon, out)
        // webloc regex without a closing tag
        timed("webloc unterminated", ShortcutInspector, "a.webloc", ("<plist><key>URL</key><string>".repeat(8000)).toByteArray(), out)
        timed("ics deep", IcsInspector, "a.ics", ("BEGIN:VCALENDAR\n" + "BEGIN:VEVENT\n".repeat(200_000)).toByteArray(), out)
        timed("vcf many", VcfInspector, "a.vcf", ("BEGIN:VCARD\nFN:a\nEND:VCARD\n".repeat(150_000)).toByteArray(), out)
        timed("ics folded", IcsInspector, "a.ics", ("BEGIN:VCALENDAR\nSUMMARY:x\n" + " y\n".repeat(1_000_000)).toByteArray(), out)
        timed("pem many", PemInspector, "a.pem", ("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n".repeat(100_000)).toByteArray(), out)
        timed("pem begin only", PemInspector, "a.pem", ("-----BEGIN CERTIFICATE-----\n".repeat(300_000)).toByteArray(), out)
        timed("pgp begins", PgpInspector, "a.asc", ("-----BEGIN PGP MESSAGE-----\n".repeat(300_000)).toByteArray(), out)
        timed("eml multipart nest", EmlInspector, "a.eml", ("Content-Type: multipart/mixed; boundary=\"b\"\n\n" + "--b\nContent-Type: multipart/mixed; boundary=\"b\"\n\n".repeat(100_000)).toByteArray(), out)
        timed("eml encoded words", EmlInspector, "a.eml", ("Subject: " + "=?utf-8?Q?a?= ".repeat(200_000) + "\n\nbody").toByteArray(), out)
        timed("eml params", EmlInspector, "a.eml", ("Content-Type: text/plain; " + "a=\"\\\\\";".repeat(300_000) + "\n\nbody").toByteArray(), out)
        timed("ssh long", SshPublicKeyInspector, "a.pub", ("ssh-rsa " + "A".repeat(250_000) + " ").toByteArray(), out)
        timed("ssh spaces", SshPublicKeyInspector, "a.pub", (" ".repeat(250_000) + "x").toByteArray(), out)
        timed("ssh many keys", SshPublicKeyInspector, "a.pub", ("ssh-rsa AAAA\n".repeat(20_000)).toByteArray(), out)
        timed("text 8MB", TextFileInspector, "a.txt", ByteArray(8 shl 20) { 'a'.code.toByte() }, out)
        timed("text many lines", TextFileInspector, "a.txt", "ab \n".repeat(1_000_000).toByteArray(), out)
        timed("der deep", DerInspector, "a.der", ByteArray(4 shl 20) { if (it % 2 == 0) 0x30 else 0x00 }, out)
        timed("der seq chain", DerInspector, "a.der", ("\u0030\u0080".repeat(10)).toByteArray(Charsets.ISO_8859_1), out)
        timed("torrent big list", TorrentInspector, "a.torrent", ("d4:infod5:filesl" + "d6:lengthi1e4:pathl1:aee".repeat(300_000) + "ee4:name1:xee").toByteArray(), out)
        timed("torrent nested", TorrentInspector, "a.torrent", ("d4:info" + "l".repeat(5_000_000)).toByteArray(), out)
        timed("torrent ints", TorrentInspector, "a.torrent", ("d4:infod5:filesl" + "d6:lengthi9223372036854775807e4:pathl1:aee".repeat(10) + "ee4:name1:xee").toByteArray(), out)
        assertTrue(out.joinToString("\n"), out.isEmpty())
    }

    @Test fun nonMatchingReturnsNull() {
        val junk = ByteArray(5000).also { Random(1).nextBytes(it) }
        val out = ArrayList<String>()
        for (i in TextCryptoInspectors.all) {
            if (i === TextFileInspector) continue
            val r = try { i.inspect(ByteArraySource(junk), "x." + i.formatIds.first()) } catch (t: Throwable) { out.add("${i.formatIds} threw"); null }
            if (r != null) out.add("${i.formatIds.first()} returned non-null for random bytes: ${r.title}")
            val r2 = i.inspect(ByteArraySource(ByteArray(0)), "x." + i.formatIds.first())
            if (r2 != null && i !== TextFileInspector) out.add("${i.formatIds.first()} returned non-null for empty input: ${r2.title}")
        }
        assertTrue(out.joinToString("\n"), out.isEmpty())
    }
}
