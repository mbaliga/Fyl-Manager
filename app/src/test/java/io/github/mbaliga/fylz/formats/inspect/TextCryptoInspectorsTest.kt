package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32

class TextCryptoInspectorsTest {
    // ---------- helpers ----------
    private fun src(b: ByteArray) = ByteArraySource(b)
    private fun src(s: String) = ByteArraySource(s.toByteArray(Charsets.UTF_8))
    private fun fact(r: InspectionResult?, label: String, group: String? = null): String? =
        r!!.facts.firstOrNull { it.label == label && (group == null || it.group == group) }?.value
    private fun concat(vararg p: ByteArray): ByteArray { val o = ByteArrayOutputStream(); p.forEach { o.write(it) }; return o.toByteArray() }
    private fun sha(alg: String, b: ByteArray) = MessageDigest.getInstance(alg).digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun u32be(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun u32le(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun u16le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun u64le(v: Long) = u32le(v and 0xFFFFFFFFL) + u32le(v ushr 32)
    private fun filetime(unix: Long) = (unix + 11644473600L) * 10_000_000L

    private fun noThrowOnPrefixes(i: FormatInspector, b: ByteArray, name: String?) {
        for (n in 0..b.size) i.inspect(src(b.copyOf(n)), name)
    }

    private fun hostileHuge(i: FormatInspector, name: String?) {
        // A source that claims 50 GB of zeros must still be answered after a bounded read.
        val s = object : ByteSource {
            override val size: Long = 50L * 1024 * 1024 * 1024
            override fun read(offset: Long, length: Int): ByteArray = ByteArray(minOf(length, 1 shl 20))
        }
        i.inspect(s, name)
    }

    // ---------- DER builders ----------
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
    private fun certificate(): ByteArray {
        val san = seq(tlv(0x82, "example.test".toByteArray()), tlv(0x82, "www.example.test".toByteArray()))
        val exts = tlv(0xA3, seq(
            seq(oid("2.5.29.19"), tlv(1, byteArrayOf(0xFF.toByte())), tlv(4, seq(tlv(1, byteArrayOf(0xFF.toByte()))))),
            seq(oid("2.5.29.17"), tlv(4, san)),
        ))
        val sigAlg = seq(oid("1.2.840.113549.1.1.11"), tlv(5, ByteArray(0)))
        val tbs = seq(
            tlv(0xA0, tlv(2, byteArrayOf(2))), tlv(2, byteArrayOf(0x0A, 0xBC.toByte())), sigAlg,
            name("Test CA"),
            seq(tlv(0x17, "240102030405Z".toByteArray()), tlv(0x18, "20340102030405Z".toByteArray())),
            name("example.test", "Fylz Org"), rsaSpki(), exts,
        )
        return seq(tbs, sigAlg, tlv(3, concat(byteArrayOf(0), ByteArray(32) { 7 })))
    }
    private fun pem(label: String, der: ByteArray, headers: String = ""): String =
        "-----BEGIN $label-----\n$headers" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $label-----\n"

    // ---------- text ----------
    @Test fun textCrlfTabs() {
        val r = TextFileInspector.inspect(src("hello world\r\nsecond line\r\n\tindented\r\n"), "a.txt")
        assertEquals("ASCII", fact(r, "Encoding"))
        assertEquals("3", fact(r, "Lines"))
        assertEquals("5", fact(r, "Words"))
        assertEquals("CRLF", fact(r, "Line endings"))
        assertEquals("tabs (1 lines)", fact(r, "Indentation"))
        assertEquals("11 characters", fact(r, "Longest line"))
        assertEquals("yes", fact(r, "Ends with newline"))
    }
    @Test fun textBomUtf8AndMixed() {
        val b = concat(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), "caf\u00e9\nx\r\ny".toByteArray())
        val r = TextFileInspector.inspect(src(b), null)
        assertEquals("UTF-8", fact(r, "Encoding"))
        assertEquals("UTF-8 BOM", fact(r, "Byte order mark"))
        assertEquals("Mixed (LF 1, CRLF 1)", fact(r, "Line endings"))
        assertEquals("3", fact(r, "Lines"))
        assertEquals("no", fact(r, "Ends with newline"))
        assertTrue(r!!.text!!.startsWith("caf\u00e9"))
    }
    @Test fun textUtf16() {
        val bom = TextFileInspector.inspect(src(concat(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), "Hi there\n".toByteArray(Charsets.UTF_16LE))), null)
        assertEquals("UTF-16LE", fact(bom, "Encoding"))
        assertEquals("2", fact(bom, "Words"))
        val noBomLe = TextFileInspector.inspect(src("Hello, world\n".toByteArray(Charsets.UTF_16LE)), null)
        assertEquals("UTF-16LE (detected, no BOM)", fact(noBomLe, "Encoding"))
        assertEquals("2", fact(noBomLe, "Words"))
        val noBomBe = TextFileInspector.inspect(src("Hello, world\n".toByteArray(Charsets.UTF_16BE)), null)
        assertEquals("UTF-16BE (detected, no BOM)", fact(noBomBe, "Encoding"))
    }
    @Test fun textLatin1AndBinary() {
        val r = TextFileInspector.inspect(src(byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())), null)
        assertEquals("ISO-8859-1 / Windows-1252 (assumed; not valid UTF-8)", fact(r, "Encoding"))
        assertEquals("caf\u00e9", r!!.text)
        val bin = TextFileInspector.inspect(src(byteArrayOf(1, 0, 2, 0, 0, 3, 0, 0, 0, 9)), null)
        assertEquals(1, bin!!.warnings.count { it.contains("NUL") })
    }
    @Test fun textShebangAndModeline() {
        val r = TextFileInspector.inspect(src("#!/usr/bin/env python3\nprint(1)\n    x = 1\n"), "run")
        assertEquals("python3", fact(r, "Interpreter"))
        assertEquals("Python (from shebang)", fact(r, "Likely language"))
        assertEquals("spaces (1 lines)", fact(r, "Indentation"))
        val sh = TextFileInspector.inspect(src("#!/bin/bash -e\necho\n"), null)
        assertEquals("/bin/bash", fact(sh, "Interpreter"))
        assertEquals("Shell script (bash) (from shebang)", fact(sh, "Likely language"))
        val vim = TextFileInspector.inspect(src("-- hi\n-- vim: set ft=lua:\n"), null)
        assertEquals("lua (Vim modeline)", fact(vim, "Likely language"))
        val emacs = TextFileInspector.inspect(src("# -*- mode: ruby -*-\nputs 1\n"), null)
        assertEquals("ruby (Emacs mode line)", fact(emacs, "Likely language"))
    }
    @Test fun textRobust() {
        val b = "#!/bin/sh\r\n\tfoo bar\n".toByteArray()
        noThrowOnPrefixes(TextFileInspector, b, "a.txt")
        hostileHuge(TextFileInspector, "a.txt")
        assertEquals("Empty text file", TextFileInspector.inspect(src(ByteArray(0)), null)!!.title)
    }

    // ---------- eml / mbox ----------
    private fun eml(): ByteArray {
        val from = "=?UTF-8?B?" + Base64.getEncoder().encodeToString("J\u00fcrgen".toByteArray()) + "?= <j@example.test>"
        return listOf(
            "From: $from", "To: Bob <bob@example.test>", "Subject: =?UTF-8?Q?Caf=C3=A9_menu?=",
            "Date: Mon, 1 Jan 2024 10:00:00 +0000", "Message-ID: <abc@example.test>", "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"BOUND\"", "",
            "--BOUND", "Content-Type: text/plain; charset=utf-8", "Content-Transfer-Encoding: quoted-printable", "",
            "Hello caf=C3=A9", "--BOUND", "Content-Type: application/pdf; name=\"report.pdf\"",
            "Content-Disposition: attachment; filename=\"report.pdf\"", "Content-Transfer-Encoding: base64", "",
            "JVBERi0xLjQK", "--BOUND--", "",
        ).joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
    }
    @Test fun emlMultipart() {
        val r = EmlInspector.inspect(src(eml()), "m.eml")
        assertEquals("J\u00fcrgen <j@example.test>", fact(r, "From"))
        assertEquals("Caf\u00e9 menu", fact(r, "Subject"))
        assertEquals("Caf\u00e9 menu", r!!.title)
        assertEquals("<abc@example.test>", fact(r, "Message-ID"))
        assertEquals("Mon, 1 Jan 2024 10:00:00 +0000", fact(r, "Date"))
        assertEquals("2", fact(r, "Parts"))
        assertEquals("1", fact(r, "Attachments"))
        assertEquals("application/pdf, \"report.pdf\", attachment, 9 B", fact(r, "Part 1.2"))
        assertEquals("text/plain, charset utf-8, 11 B", fact(r, "Part 1.1"))
        assertEquals("Hello caf\u00e9", r.text)
    }
    @Test fun emlRfc2047Adjacent() {
        val s = "Subject: =?utf-8?Q?a_=E2=82?= =?utf-8?Q?=AC_b?=\nFrom: x@y.test\n\nbody"
        val r = EmlInspector.inspect(src(s), null)
        assertEquals("a \u20ac b", fact(r, "Subject"))
        assertEquals("body", r!!.text)
    }
    @Test fun emlRobust() {
        noThrowOnPrefixes(EmlInspector, eml(), "m.eml")
        hostileHuge(EmlInspector, "m.eml")
        assertNull(EmlInspector.inspect(src("just some words"), null))
        val hostile = "Subject: x\nContent-Type: multipart/mixed; boundary=\"4294967295\"\n\n--4294967295\nContent-Length: 4294967295\n\nabc\n"
        assertNotNull(EmlInspector.inspect(src(hostile), null))
    }
    @Test fun mboxMessages() {
        val s = "From alice@example.test Mon Jan  1 10:00:00 2024\nFrom: alice@example.test\nSubject: First\n\nbody\n\n" +
            "From bob@example.test Tue Jan  2 10:00:00 2024\nFrom: bob@example.test\nSubject: =?UTF-8?B?" +
            Base64.getEncoder().encodeToString("Zw\u00e4".toByteArray()) + "?=\n\nhi\n"
        val r = MboxInspector.inspect(src(s), "x.mbox")
        assertEquals("2", fact(r, "Messages"))
        assertEquals("First - alice@example.test", fact(r, "Message 1"))
        assertEquals("Zw\u00e4 - bob@example.test", fact(r, "Message 2"))
        noThrowOnPrefixes(MboxInspector, s.toByteArray(), null)
        hostileHuge(MboxInspector, null)
        assertNull(MboxInspector.inspect(src("hello"), null))
    }

    // ---------- vcf / ics ----------
    private val vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Doe;Jane;;;\r\nFN:Jane Doe\r\nORG:Acme\\, Inc.;Research\r\nEMAIL:jane@example.test\r\n" +
        "TEL;TYPE=CELL:+1 555 0100\r\nEND:VCARD\r\nBEGIN:VCARD\r\nVERSION:3.0\r\nFN:John\r\n  Smith\r\nEND:VCARD\r\n"
    @Test fun vcfContacts() {
        val r = VcfInspector.inspect(src(vcf), "c.vcf")
        assertEquals("2", fact(r, "Contacts"))
        assertEquals("3.0", fact(r, "vCard version"))
        assertEquals("1", fact(r, "Email addresses"))
        assertEquals("1", fact(r, "Phone numbers"))
        assertEquals("Jane Doe (Acme, Inc., Research)", fact(r, "Contact 1"))
        assertEquals("John Smith", fact(r, "Contact 2"))
        noThrowOnPrefixes(VcfInspector, vcf.toByteArray(), null)
        hostileHuge(VcfInspector, null)
        assertNull(VcfInspector.inspect(src("nothing"), null))
    }
    private val ics = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Fylz//Test//EN", "X-WR-CALNAME:Team",
        "BEGIN:VTIMEZONE", "TZID:Europe/Paris", "BEGIN:STANDARD", "DTSTART:19701025T030000", "END:STANDARD", "END:VTIMEZONE",
        "BEGIN:VEVENT", "SUMMARY:Kickoff\\, big", "DTSTART;TZID=Europe/Paris:20240301T090000",
        "DTEND;TZID=Europe/Paris:20240301T100000", "RRULE:FREQ=WEEKLY", "BEGIN:VALARM", "SUMMARY:ignored", "END:VALARM", "END:VEVENT",
        "BEGIN:VEVENT", "SUMMARY:Holiday", "DTSTART;VALUE=DATE:20240501", "DTEND;VALUE=DATE:20240502", "END:VEVENT",
        "BEGIN:VEVENT", "SUMMARY:Utc", "DTSTART:20240601T120000Z", "END:VEVENT", "END:VCALENDAR", "",
    ).joinToString("\r\n")
    @Test fun icsEvents() {
        val r = IcsInspector.inspect(src(ics), "c.ics")
        assertEquals("3", fact(r, "Events"))
        assertEquals("Team", r!!.title)
        assertEquals("Europe/Paris", fact(r, "Time zones"))
        assertEquals("-//Fylz//Test//EN", fact(r, "Product"))
        assertEquals("1", fact(r, "Recurring events"))
        assertEquals("1", fact(r, "Alarms"))
        assertEquals("Kickoff, big - 2024-03-01 09:00:00 (Europe/Paris) to 2024-03-01 10:00:00 (Europe/Paris)", fact(r, "Event 1"))
        assertEquals("Holiday - 2024-05-01 to 2024-05-02", fact(r, "Event 2"))
        assertEquals("Utc - 2024-06-01 12:00:00 UTC", fact(r, "Event 3"))
        assertEquals("2024-03-01 09:00:00 (Europe/Paris)", fact(r, "Earliest start"))
        noThrowOnPrefixes(IcsInspector, ics.toByteArray(), null)
        hostileHuge(IcsInspector, null)
        assertNull(IcsInspector.inspect(src("hello"), null))
    }

    // ---------- certificates / PEM / DER ----------
    @Test fun derCertificate() {
        val der = certificate()
        val r = DerInspector.inspect(src(der), "site.crt")
        assertEquals("crt", r!!.formatId)
        assertEquals("O=Fylz Org, CN=example.test", fact(r, "Subject"))
        assertEquals("CN=Test CA", fact(r, "Issuer"))
        assertEquals("0abc", fact(r, "Serial number"))
        assertEquals("v3", fact(r, "Version"))
        assertEquals("2024-01-02 03:04:05 UTC", fact(r, "Not before"))
        assertEquals("2034-01-02 03:04:05 UTC", fact(r, "Not after"))
        assertEquals("RSA 2048 bits", fact(r, "Public key"))
        assertEquals("sha256WithRSAEncryption", fact(r, "Signature algorithm"))
        assertEquals("DNS:example.test, DNS:www.example.test", fact(r, "Subject alternative names"))
        assertEquals("yes", fact(r, "Is CA"))
        assertEquals(sha("SHA-256", der).joinToString(":") { "%02x".format(it) }, fact(r, "SHA-256 fingerprint"))
        assertNull(fact(r, "Self-issued"))
        noThrowOnPrefixes(DerInspector, der, "site.der")
    }
    @Test fun derHostileLengths() {
        val huge = byteArrayOf(0x30, 0x84.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1, 2, 3)
        val r = DerInspector.inspect(src(huge), "x.der")
        assertNull(r)
        val nested = ByteArray(40) { if (it % 2 == 0) 0x30 else 0x84.toByte() }
        DerInspector.inspect(src(nested), "x.der")
        // a certificate whose outer length claims 0xFFFFFFFF
        val c = certificate().copyOf()
        val patched = concat(byteArrayOf(0x30, 0x84.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), c.copyOfRange(4, c.size))
        DerInspector.inspect(src(patched), "x.cer")
        hostileHuge(DerInspector, "x.der")
    }
    @Test fun pkcs12Detected() {
        val p12 = seq(tlv(2, byteArrayOf(3)), seq(oid("1.2.840.113549.1.7.1"), tlv(0xA0, tlv(4, ByteArray(10)))), seq(seq(oid("1.3.14.3.2.26")), tlv(4, ByteArray(20))))
        val r = DerInspector.inspect(src(p12), "keys.p12")
        assertEquals("PKCS#12 key store", r!!.title)
        assertEquals("p12", r.formatId)
        assertEquals("yes", fact(r, "Has MAC"))
        noThrowOnPrefixes(DerInspector, p12, "keys.p12")
    }
    @Test fun pemBundle() {
        val der = certificate()
        val pkcs1 = seq(tlv(2, modulus), tlv(2, byteArrayOf(1, 0, 1)))
        val text = pem("CERTIFICATE", der) + "junk between\n" + pem("RSA PUBLIC KEY", pkcs1) +
            pem("RSA PRIVATE KEY", ByteArray(16) { 1 }, "Proc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,0011223344556677\n\n")
        val r = PemInspector.inspect(src(text), "bundle.pem")
        assertEquals("3", fact(r, "Blocks"))
        assertEquals("x1", fact(r, "CERTIFICATE"))
        assertEquals("O=Fylz Org, CN=example.test", fact(r, "Subject", "Block 1 (CERTIFICATE)"))
        assertEquals("RSA 2048 bits", fact(r, "Public key", "Block 2 (RSA PUBLIC KEY)"))
        assertEquals("yes (AES-128-CBC)", fact(r, "Encrypted", "Block 3 (RSA PRIVATE KEY)"))
        assertEquals("DNS:example.test, DNS:www.example.test", fact(r, "Subject alternative names"))
        val crt = DerInspector.inspect(src(pem("CERTIFICATE", der)), "a.crt")
        assertEquals("crt", crt!!.formatId)
        assertEquals("CN=Test CA", fact(crt, "Issuer"))
        noThrowOnPrefixes(PemInspector, text.toByteArray(), "b.pem")
        hostileHuge(PemInspector, "b.pem")
        assertNull(PemInspector.inspect(src("no armour here"), null))
        // hostile: an END-less block with a very long body is bounded and harmless
        PemInspector.inspect(src("-----BEGIN CERTIFICATE-----\n" + "A".repeat(100000)), null)
    }

    // ---------- PGP ----------
    private fun pgpKeyPacket(): ByteArray {
        val n = ByteArray(256) { (0x80 or (it and 0x7F)).toByte() }
        val body = concat(byteArrayOf(4), u32be(1600000000L), byteArrayOf(1), byteArrayOf(0x08, 0x00), n, byteArrayOf(0x00, 0x11, 1, 0, 1))
        return concat(byteArrayOf(0xC6.toByte()), byteArrayOf(0xFF.toByte()), u32be(body.size.toLong()), body)
    }
    private fun pgpBytes(): ByteArray {
        val uid = "Alice <a@x.test>".toByteArray()
        return concat(pgpKeyPacket(), byteArrayOf(0xCD.toByte(), uid.size.toByte()), uid)
    }
    @Test fun pgpArmor() {
        val raw = pgpBytes()
        val text = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nVersion: Test 1.0\n\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(raw) + "\n=ABCD\n-----END PGP PUBLIC KEY BLOCK-----\n"
        val r = PgpInspector.inspect(src(text), "k.asc")
        assertEquals("public key block", fact(r, "Blocks"))
        assertEquals("Test 1.0", fact(r, "Version"))
        assertEquals("Alice <a@x.test>", fact(r, "User ID"))
        assertEquals("v4 RSA 2048 bits, created 2020-09-13 12:26:40 UTC", fact(r, "Public key"))
        val bodyLen = Bytes.u32be(raw, 2).toInt()
        val hashed = concat(byteArrayOf(0x99.toByte(), (bodyLen shr 8).toByte(), bodyLen.toByte()), raw.copyOfRange(6, 6 + bodyLen))
        assertEquals(hex(sha("SHA-1", hashed)).uppercase(), fact(r, "Fingerprint (v4)"))
        noThrowOnPrefixes(PgpInspector, text.toByteArray(), "k.asc")
        val bin = PgpInspector.inspect(src(raw), "k.gpg")
        assertEquals("OpenPGP binary data", bin!!.title)
        assertEquals("Public key, User ID", fact(bin, "Packets (first 2)"))
        noThrowOnPrefixes(PgpInspector, raw, "k.gpg")
        assertNull(PgpInspector.inspect(src("plain"), null))
    }
    @Test fun pgpHostile() {
        val hostile = byteArrayOf(0xC6.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 4, 0, 0, 0, 0, 1)
        PgpInspector.inspect(src(hostile), "x.gpg")
        val old = byteArrayOf(0x99.toByte().let { (it.toInt() or 0x02).toByte() }, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 4)
        PgpInspector.inspect(src(old), "x.pgp")
        hostileHuge(PgpInspector, "x.asc")
    }

    // ---------- SSH ----------
    private fun sstr(b: ByteArray) = concat(u32be(b.size.toLong()), b)
    private fun sstr(s: String) = sstr(s.toByteArray())
    private val edPub = ByteArray(32) { (it + 1).toByte() }
    private fun edBlob() = concat(sstr("ssh-ed25519"), sstr(edPub))
    @Test fun sshPublic() {
        val line = "ssh-ed25519 " + Base64.getEncoder().encodeToString(edBlob()) + " me@box\n"
        val r = SshPublicKeyInspector.inspect(src(line), "id.pub")
        assertEquals("ssh-ed25519", fact(r, "Key type"))
        assertEquals("256 bits", fact(r, "Key size"))
        assertEquals("me@box", fact(r, "Comment"))
        assertEquals("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha("SHA-256", edBlob())), fact(r, "SHA-256 fingerprint"))
        val rsaBlob = concat(sstr("ssh-rsa"), sstr(byteArrayOf(1, 0, 1)), sstr(modulus))
        val rsa = SshPublicKeyInspector.inspect(src("ssh-rsa " + Base64.getEncoder().encodeToString(rsaBlob) + "\n"), null)
        assertEquals("2048 bits", fact(rsa, "Key size"))
        noThrowOnPrefixes(SshPublicKeyInspector, line.toByteArray(), "id.pub")
        // hostile length inside the blob
        val bad = concat(sstr("ssh-rsa").copyOf(11), u32be(0xFFFFFFFFL), byteArrayOf(1, 2))
        SshPublicKeyInspector.inspect(src("ssh-rsa " + Base64.getEncoder().encodeToString(bad)), null)
        hostileHuge(SshPublicKeyInspector, "id.pub")
        assertNull(SshPublicKeyInspector.inspect(src("hello world"), null))
    }
    @Test fun sshPrivate() {
        val priv = concat(u32be(0xDEADBEEFL), u32be(0xDEADBEEFL), sstr("ssh-ed25519"), sstr(edPub), sstr(ByteArray(64)), sstr("me@box"), byteArrayOf(1, 2, 3))
        val file = concat("openssh-key-v1\u0000".toByteArray(), sstr("none"), sstr("none"), sstr(""), u32be(1), sstr(edBlob()), sstr(priv))
        val text = pem("OPENSSH PRIVATE KEY", file)
        val r = PemInspector.inspect(src(text), "id_ed25519")
        val g = "Block 1 (OPENSSH PRIVATE KEY)"
        assertEquals("none", fact(r, "Cipher", g))
        assertEquals("no", fact(r, "Encrypted", g))
        assertEquals("ssh-ed25519", fact(r, "Key type", g))
        assertEquals("me@box", fact(r, "Comment", g))
        assertEquals("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha("SHA-256", edBlob())), fact(r, "SHA-256 fingerprint", g))
        val enc = concat("openssh-key-v1\u0000".toByteArray(), sstr("aes256-ctr"), sstr("bcrypt"), sstr(ByteArray(20)), u32be(1), sstr(edBlob()), sstr(ByteArray(64)))
        val re = PemInspector.inspect(src(pem("OPENSSH PRIVATE KEY", enc)), null)
        assertEquals("yes (passphrase protected)", fact(re, "Encrypted"))
        assertNull(fact(re, "Comment"))
        noThrowOnPrefixes(PemInspector, text.toByteArray(), "id")
        val hostile = concat("openssh-key-v1\u0000".toByteArray(), u32be(0xFFFFFFFFL), byteArrayOf(1))
        PemInspector.inspect(src(pem("OPENSSH PRIVATE KEY", hostile)), null)
        val hostileCount = concat("openssh-key-v1\u0000".toByteArray(), sstr("none"), sstr("none"), sstr(""), u32be(0xFFFFFFFFL))
        PemInspector.inspect(src(pem("OPENSSH PRIVATE KEY", hostileCount)), null)
    }

    // ---------- lnk ----------
    private fun utf16(s: String) = concat(u16le(s.length), s.toByteArray(Charsets.UTF_16LE))
    private fun lnk(): ByteArray {
        val clsid = intArrayOf(0x01, 0x14, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46)
        val ft = filetime(1600000000L)
        val flags = 2L or 4L or 8L or 16L or 32L or 0x80L
        val header = concat(u32le(0x4C), ByteArray(16) { clsid[it].toByte() }, u32le(flags), u32le(0x20),
            u64le(ft), u64le(ft + 10_000_000L * 60), u64le(ft + 10_000_000L * 3600), u32le(1536), u32le(0), u32le(1), u16le(0), ByteArray(10))
        assertEquals(76, header.size)
        val vol = concat(u32le(17), u32le(3), u32le(0x12345678), u32le(0x10), byteArrayOf(0))
        val basePath = "C:\\Windows\\notepad.exe\u0000".toByteArray(Charsets.ISO_8859_1)
        val suffix = byteArrayOf(0)
        val hdrSize = 0x1C
        val volOff = hdrSize
        val baseOff = volOff + vol.size
        val sufOff = baseOff + basePath.size
        val total = sufOff + suffix.size
        val li = concat(u32le(total.toLong()), u32le(hdrSize.toLong()), u32le(1), u32le(volOff.toLong()), u32le(baseOff.toLong()), u32le(0), u32le(sufOff.toLong()), vol, basePath, suffix)
        assertEquals(total, li.size)
        return concat(header, li, utf16("Notepad"), utf16("..\\notepad.exe"), utf16("C:\\Windows"), utf16("/p readme.txt"))
    }
    @Test fun lnkFacts() {
        val r = LnkInspector.inspect(src(lnk()), "n.lnk")
        assertEquals("C:\\Windows\\notepad.exe", fact(r, "Target path"))
        assertEquals("2020-09-13 12:26:40 UTC", fact(r, "Target created"))
        assertEquals("2020-09-13 12:27:40 UTC", fact(r, "Target accessed"))
        assertEquals("2020-09-13 13:26:40 UTC", fact(r, "Target modified"))
        assertEquals("1.5 KiB", fact(r, "Target size"))
        assertEquals("fixed disk", fact(r, "Volume type"))
        assertEquals("12345678", fact(r, "Volume serial"))
        assertEquals("Notepad", fact(r, "Description"))
        assertEquals("..\\notepad.exe", fact(r, "Relative path"))
        assertEquals("C:\\Windows", fact(r, "Working directory"))
        assertEquals("/p readme.txt", fact(r, "Arguments"))
        assertEquals("normal window", fact(r, "Window"))
        noThrowOnPrefixes(LnkInspector, lnk(), "n.lnk")
        val bad = lnk().copyOf()
        for (i in 0 until 4) bad[76 + i] = 0xFF.toByte()
        LnkInspector.inspect(src(bad), "n.lnk")
        val bad2 = lnk().copyOf()
        for (i in 0 until 4) { bad2[76 + 12 + i] = 0xFF.toByte(); bad2[76 + 16 + i] = 0xFF.toByte(); bad2[76 + 24 + i] = 0xFF.toByte() }
        LnkInspector.inspect(src(bad2), "n.lnk")
        hostileHuge(LnkInspector, "n.lnk")
        assertNull(LnkInspector.inspect(src("not a link"), null))
    }

    // ---------- shortcuts ----------
    @Test fun shortcuts() {
        val url = ShortcutInspector.inspect(src("[InternetShortcut]\r\nURL=https://example.test/a?b=1\r\nIconFile=x.ico\r\nIconIndex=0\r\n"), "Example Site.url")
        assertEquals("url", url!!.formatId)
        assertEquals("https://example.test/a?b=1", fact(url, "Target"))
        assertEquals("Example Site", fact(url, "Name"))
        assertEquals("x.ico", fact(url, "IconFile"))
        val web = ShortcutInspector.inspect(src("<?xml version=\"1.0\"?>\n<plist version=\"1.0\"><dict><key>URL</key>\n<string>https://example.test/?a=1&amp;b=2</string></dict></plist>"), "Link.webloc")
        assertEquals("webloc", web!!.formatId)
        assertEquals("https://example.test/?a=1&b=2", fact(web, "Target"))
        val desk = ShortcutInspector.inspect(src("[Desktop Entry]\nType=Application\nName=Editor\nName[de]=Bearbeiter\nExec=editor %F\nIcon=editor\nTerminal=false\n\n[Desktop Action new]\nName=New\n"), "editor.desktop")
        assertEquals("desktop", desk!!.formatId)
        assertEquals("Editor", fact(desk, "Name"))
        assertEquals("editor %F", fact(desk, "Exec"))
        assertEquals("editor", fact(desk, "Icon"))
        assertEquals("1", fact(desk, "Actions"))
        assertNull(ShortcutInspector.inspect(src("hello"), "a.url"))
        for (t in listOf("[InternetShortcut]\nURL=http://a.test\n", "[Desktop Entry]\nName=x\n")) noThrowOnPrefixes(ShortcutInspector, t.toByteArray(), "a.url")
        hostileHuge(ShortcutInspector, "a.url")
    }

    // ---------- registry hive / evtx ----------
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
    @Test fun registryHive() {
        val r = RegistryHiveInspector.inspect(src(regf()), "SOFTWARE")
        assertEquals("1.5", fact(r, "Version"))
        assertEquals("2020-09-13 12:26:40 UTC", fact(r, "Last written"))
        assertEquals("\\??\\C:\\Config\\SOFTWARE", fact(r, "Hive name"))
        assertEquals("SOFTWARE", r!!.summary)
        assertEquals("valid", fact(r, "Header checksum"))
        assertEquals("primary hive", fact(r, "File type"))
        assertTrue(r.warnings.isEmpty())
        val dirty = regf(); dirty[8] = 9
        val rd = RegistryHiveInspector.inspect(src(dirty), null)
        assertTrue(rd!!.warnings.any { it.contains("sequence") })
        assertTrue(rd.warnings.any { it.contains("checksum") })
        noThrowOnPrefixes(RegistryHiveInspector, regf(), null)
        val h = regf(); for (i in 0 until 4) { h[0x24 + i] = 0xFF.toByte(); h[0x28 + i] = 0xFF.toByte() }
        RegistryHiveInspector.inspect(src(h), null)
        hostileHuge(RegistryHiveInspector, null)
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
    @Test fun evtxHeader() {
        val r = EvtxInspector.inspect(src(evtx()), "Security.evtx")
        assertEquals("3.1", fact(r, "Version"))
        assertEquals("3", fact(r, "Chunk count"))
        assertEquals("100", fact(r, "Next record ID"))
        assertEquals("valid", fact(r, "Header checksum"))
        assertEquals("none", fact(r, "Flags"))
        noThrowOnPrefixes(EvtxInspector, evtx(), null)
        EvtxInspector.inspect(src(evtx(0xFFFF)), null)
        hostileHuge(EvtxInspector, null)
        assertNull(EvtxInspector.inspect(src("ElfChnk\u0000"), null))
    }

    // ---------- torrent ----------
    private fun bs(s: String) = "${s.length}:$s"
    private val pieces = "A".repeat(20)
    private val infoSingle = "d6:lengthi12345e4:name8:file.bin12:piece lengthi16384e6:pieces20:${pieces}7:privatei1ee"
    private fun torrent(info: String = infoSingle): String =
        "d8:announce${bs("http://t.example/announce")}13:announce-listll${bs("http://t.example/announce")}el${bs("udp://u.example:80")}ee" +
            "13:creation datei1600000000e10:created by${bs("Fylz")}4:info${info}e"
    @Test fun torrentSingle() {
        val t = torrent()
        val r = TorrentInspector.inspect(src(t.toByteArray(Charsets.ISO_8859_1)), "x.torrent")
        assertEquals("file.bin", fact(r, "Name"))
        assertEquals("1", fact(r, "Files"))
        assertEquals("12.1 KiB (12345 bytes)", fact(r, "Total size"))
        assertEquals("16.0 KiB", fact(r, "Piece length"))
        assertEquals("1", fact(r, "Pieces"))
        assertEquals("yes", fact(r, "Private"))
        assertEquals("2", fact(r, "Trackers"))
        assertEquals("http://t.example/announce", fact(r, "Tracker 1"))
        assertEquals("udp://u.example:80", fact(r, "Tracker 2"))
        assertEquals("2020-09-13 12:26:40 UTC", fact(r, "Created"))
        assertEquals("Fylz", fact(r, "Created by"))
        val expected = hex(sha("SHA-1", infoSingle.toByteArray(Charsets.ISO_8859_1)))
        assertEquals(expected, fact(r, "Info hash (SHA-1)"))
        assertEquals("magnet:?xt=urn:btih:$expected", fact(r, "Magnet link"))
        assertTrue(r!!.warnings.isEmpty())
        noThrowOnPrefixes(TorrentInspector, t.toByteArray(Charsets.ISO_8859_1), "x.torrent")
    }
    @Test fun torrentMultiFile() {
        val info = "d5:filesld6:lengthi100e4:pathl1:a5:b.txteed6:lengthi50e4:pathl5:c.txteee4:name4:root12:piece lengthi16384e6:pieces20:${pieces}e"
        val r = TorrentInspector.inspect(src(torrent(info).toByteArray(Charsets.ISO_8859_1)), "m.torrent")
        assertEquals("2", fact(r, "Files"))
        assertEquals("150 B (150 bytes)", fact(r, "Total size"))
        assertEquals("a/b.txt (100 B)", fact(r, "File 1"))
        assertEquals("c.txt (50 B)", fact(r, "File 2"))
        assertEquals("no", fact(r, "Private"))
        assertEquals(hex(sha("SHA-1", info.toByteArray(Charsets.ISO_8859_1))), fact(r, "Info hash (SHA-1)"))
    }
    @Test fun torrentHostile() {
        for (s in listOf("d4:infod4:name4294967295:x", "d4:info" + "l".repeat(100000), "d4:infod6:lengthi99999999999999999999999ee4:name1:xee",
            "d99999999999:x", "i4294967295e", "d4:infod5:filesl" + "d6:lengthi1e4:pathl1:aee".repeat(2000) + "ee4:name1:xee")) {
            TorrentInspector.inspect(src(s.toByteArray(Charsets.ISO_8859_1)), "h.torrent")
        }
        hostileHuge(TorrentInspector, "h.torrent")
        assertNull(TorrentInspector.inspect(src("hello"), null))
        assertNull(TorrentInspector.inspect(src("d3:foo3:bare"), null))
    }

    // ---------- registry of inspectors ----------
    @Test fun allListsEveryInspectorOnce() {
        val all = TextCryptoInspectors.all
        assertEquals(all.size, all.toSet().size)
        val ids = all.flatMap { it.formatIds }
        for (id in listOf("txt", "eml", "mbox", "vcf-vcard", "ics", "pem", "der", "crt", "cer", "pub", "lnk", "url", "webloc", "desktop", "evtx", "torrent", "asc-pgp"))
            assertTrue(id, id in ids)
        assertEquals(ids.size, ids.toSet().size)
        for (i in all) for (id in i.formatIds) assertTrue(id == id.lowercase() && !id.startsWith("."))
    }
}
