package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.util.Locale

/** One ASN.1 TLV located inside a byte array (offsets, no copying). */
internal class Tlv(val cls: Int, val constructed: Boolean, val tag: Int, val start: Int, val vs: Int, val len: Int) {
    val end: Int get() = vs + len
    fun isUniversal(t: Int) = cls == 0 && tag == t
    fun isSeq() = cls == 0 && tag == 16 && constructed
}

internal object Der {
    fun read(b: ByteArray, off: Int, limit: Int = b.size): Tlv? {
        val lim = minOf(limit, b.size)
        if (off < 0 || off + 2 > lim) return null
        val t = b[off].toInt() and 0xFF
        var p = off + 1
        var tag = t and 0x1F
        if (tag == 0x1F) {
            tag = 0
            var n = 0
            while (true) {
                if (p >= lim || n >= 4) return null
                val x = b[p++].toInt() and 0xFF
                tag = (tag shl 7) or (x and 0x7F)
                n++
                if ((x and 0x80) == 0) break
            }
        }
        if (p >= lim) return null
        val l = b[p++].toInt() and 0xFF
        var len: Long
        if (l < 0x80) len = l.toLong() else {
            val n = l and 0x7F
            if (n == 0 || n > 4 || p + n > lim) return null
            len = 0
            for (i in 0 until n) len = (len shl 8) or (b[p + i].toInt() and 0xFF).toLong()
            p += n
        }
        if (len > Int.MAX_VALUE || p.toLong() + len > lim) return null
        return Tlv((t shr 6) and 3, (t and 0x20) != 0, tag, off, p, len.toInt())
    }

    fun children(b: ByteArray, t: Tlv, max: Int = 2000): List<Tlv> {
        val out = ArrayList<Tlv>()
        if (!t.constructed) return out
        var pos = t.vs
        while (pos < t.end && out.size < max) {
            val c = read(b, pos, t.end) ?: break
            out.add(c)
            pos = c.end
        }
        return out
    }

    fun oid(b: ByteArray, t: Tlv): String {
        val sb = StringBuilder()
        var first = true
        var acc = 0L
        var cnt = 0
        var i = t.vs
        while (i < t.end && i < b.size) {
            val x = b[i].toInt() and 0xFF
            acc = (acc shl 7) or (x and 0x7F).toLong()
            cnt++
            if (cnt > 9) return "?"
            if ((x and 0x80) == 0) {
                if (first) {
                    val a = if (acc < 40) 0 else if (acc < 80) 1 else 2
                    sb.append(a).append('.').append(acc - a * 40L)
                    first = false
                } else sb.append('.').append(acc)
                acc = 0; cnt = 0
            }
            i++
        }
        return sb.toString()
    }

    fun smallInt(b: ByteArray, t: Tlv): Int {
        if (t.len !in 1..4) return -1
        var v = 0
        for (i in 0 until t.len) v = (v shl 8) or (b[t.vs + i].toInt() and 0xFF)
        return v
    }

    fun string(b: ByteArray, t: Tlv): String = when (t.tag) {
        12 -> String(b, t.vs, t.len, Charsets.UTF_8)
        19, 22, 20, 26, 25, 18, 27 -> TC.latin1(b, t.vs, t.len)
        30 -> String(b, t.vs, t.len and -2, Charsets.UTF_16BE)
        28 -> try { String(b, t.vs, t.len and -4, java.nio.charset.Charset.forName("UTF-32BE")) } catch (e: Throwable) { TC.hex(b, t.vs, t.len) }
        else -> TC.hex(b, t.vs, minOf(t.len, 32))
    }

    /** UTCTime / GeneralizedTime to "YYYY-MM-DD HH:MM:SS UTC". */
    fun time(b: ByteArray, t: Tlv): String? {
        if (t.cls != 0 || (t.tag != 23 && t.tag != 24) || t.len > 40) return null
        val s = TC.latin1(b, t.vs, t.len)
        val digits = s.takeWhile { it.isDigit() }
        val yl = if (t.tag == 23) 2 else 4
        if (digits.length < yl + 8) return null
        var y = digits.substring(0, yl).toInt()
        if (t.tag == 23) y += if (y >= 50) 1900 else 2000
        val mo = digits.substring(yl, yl + 2)
        val d = digits.substring(yl + 2, yl + 4)
        val h = digits.substring(yl + 4, yl + 6)
        val mi = digits.substring(yl + 6, yl + 8)
        val sec = if (digits.length >= yl + 10) digits.substring(yl + 8, yl + 10) else "00"
        val rest = s.substring(digits.length)
        val zone = if (rest == "Z") " UTC" else if (rest.isEmpty()) "" else " ($rest)"
        return String.format(Locale.ROOT, "%04d-%s-%s %s:%s:%s%s", y, mo, d, h, mi, sec, zone)
    }
}

internal object Oids {
    private val names = mapOf(
        "1.2.840.113549.1.1.1" to "RSA", "1.2.840.113549.1.1.4" to "md5WithRSAEncryption",
        "1.2.840.113549.1.1.5" to "sha1WithRSAEncryption", "1.2.840.113549.1.1.10" to "RSASSA-PSS",
        "1.2.840.113549.1.1.11" to "sha256WithRSAEncryption", "1.2.840.113549.1.1.12" to "sha384WithRSAEncryption",
        "1.2.840.113549.1.1.13" to "sha512WithRSAEncryption", "1.2.840.113549.1.1.14" to "sha224WithRSAEncryption",
        "1.2.840.10045.2.1" to "EC", "1.2.840.10045.4.1" to "ecdsa-with-SHA1",
        "1.2.840.10045.4.3.1" to "ecdsa-with-SHA224", "1.2.840.10045.4.3.2" to "ecdsa-with-SHA256",
        "1.2.840.10045.4.3.3" to "ecdsa-with-SHA384", "1.2.840.10045.4.3.4" to "ecdsa-with-SHA512",
        "1.3.101.112" to "Ed25519", "1.3.101.113" to "Ed448", "1.3.101.110" to "X25519", "1.3.101.111" to "X448",
        "1.2.840.10040.4.1" to "DSA", "1.2.840.10040.4.3" to "dsa-with-sha1", "2.16.840.1.101.3.4.3.2" to "dsa-with-sha256",
        "2.5.4.3" to "CN", "2.5.4.4" to "SN", "2.5.4.5" to "serialNumber", "2.5.4.6" to "C", "2.5.4.7" to "L",
        "2.5.4.8" to "ST", "2.5.4.9" to "street", "2.5.4.10" to "O", "2.5.4.11" to "OU", "2.5.4.12" to "title",
        "2.5.4.42" to "GN", "2.5.4.17" to "postalCode", "0.9.2342.19200300.100.1.25" to "DC",
        "1.2.840.113549.1.9.1" to "emailAddress",
    )
    fun name(oid: String): String = names[oid] ?: oid

    /** curve OID to (name, bits) */
    fun curve(oid: String): Pair<String, Int>? = when (oid) {
        "1.2.840.10045.3.1.1" -> "P-192" to 192
        "1.2.840.10045.3.1.7" -> "P-256" to 256
        "1.3.132.0.34" -> "P-384" to 384
        "1.3.132.0.35" -> "P-521" to 521
        "1.3.132.0.10" -> "secp256k1" to 256
        else -> null
    }
}

internal class KeyDesc(val algorithm: String, val bits: Int?, val detail: String? = null) {
    override fun toString(): String =
        algorithm + (if (bits != null) " $bits bits" else "") + (if (detail != null) " ($detail)" else "")
}

internal object X509 {
    fun name(b: ByteArray, t: Tlv): String {
        val parts = ArrayList<String>()
        for (rdn in Der.children(b, t, 100)) {
            val avs = ArrayList<String>()
            for (ava in Der.children(b, rdn, 20)) {
                val kv = Der.children(b, ava, 4)
                if (kv.size < 2) continue
                avs.add(Oids.name(Der.oid(b, kv[0])) + "=" + TC.cap(Der.string(b, kv[1]), 300))
            }
            if (avs.isNotEmpty()) parts.add(avs.joinToString("+"))
        }
        return parts.joinToString(", ")
    }

    private fun rsaBitsFromSeq(b: ByteArray, off: Int, limit: Int): Int? {
        val seq = Der.read(b, off, limit) ?: return null
        if (!seq.isSeq()) return null
        val c = Der.children(b, seq, 4)
        if (c.isEmpty() || c[0].tag != 2) return null
        return TC.bitLen(b, c[0].vs, c[0].len)
    }

    /** Describe a SubjectPublicKeyInfo SEQUENCE. */
    fun spki(b: ByteArray, t: Tlv): KeyDesc? {
        if (!t.isSeq()) return null
        val c = Der.children(b, t, 4)
        if (c.size < 2 || !c[0].isSeq() || c[1].tag != 3) return null
        val alg = Der.children(b, c[0], 4)
        if (alg.isEmpty() || alg[0].tag != 6) return null
        val oid = Der.oid(b, alg[0])
        val bits = c[1]
        return when (oid) {
            "1.2.840.113549.1.1.1" -> KeyDesc("RSA", if (bits.len > 1) rsaBitsFromSeq(b, bits.vs + 1, bits.end) else null)
            "1.2.840.10045.2.1" -> {
                val curveOid = if (alg.size > 1 && alg[1].tag == 6) Der.oid(b, alg[1]) else null
                val cv = curveOid?.let { Oids.curve(it) }
                if (cv != null) KeyDesc("EC", cv.second, cv.first)
                else KeyDesc("EC", null, if (curveOid != null) "curve $curveOid" else null)
            }
            "1.3.101.112" -> KeyDesc("Ed25519", 256)
            "1.3.101.113" -> KeyDesc("Ed448", 456)
            "1.3.101.110" -> KeyDesc("X25519", 256)
            "1.3.101.111" -> KeyDesc("X448", 448)
            "1.2.840.10040.4.1" -> {
                var p: Int? = null
                if (alg.size > 1 && alg[1].isSeq()) {
                    val pq = Der.children(b, alg[1], 3)
                    if (pq.isNotEmpty() && pq[0].tag == 2) p = TC.bitLen(b, pq[0].vs, pq[0].len)
                }
                KeyDesc("DSA", p)
            }
            else -> KeyDesc(Oids.name(oid), null)
        }
    }

    private fun keyUsage(b: ByteArray, bs: Tlv): String? {
        if (bs.tag != 3 || bs.len < 2) return null
        val unused = b[bs.vs].toInt() and 0xFF
        val names = listOf("digitalSignature", "nonRepudiation", "keyEncipherment", "dataEncipherment",
            "keyAgreement", "keyCertSign", "cRLSign", "encipherOnly", "decipherOnly")
        val out = ArrayList<String>()
        for (i in names.indices) {
            val byteIdx = i / 8
            if (1 + byteIdx >= bs.len) break
            val totalBits = (bs.len - 1) * 8 - unused
            if (i >= totalBits) break
            if ((b[bs.vs + 1 + byteIdx].toInt() and (0x80 shr (i % 8))) != 0) out.add(names[i])
        }
        return if (out.isEmpty()) "none" else out.joinToString(", ")
    }

    private fun ipText(b: ByteArray, t: Tlv): String = when (t.len) {
        4 -> (0 until 4).joinToString(".") { (b[t.vs + it].toInt() and 0xFF).toString() }
        16 -> (0 until 8).joinToString(":") { "%x".format(Locale.ROOT, ((b[t.vs + 2 * it].toInt() and 0xFF) shl 8) or (b[t.vs + 2 * it + 1].toInt() and 0xFF)) }
        else -> TC.hex(b, t.vs, t.len)
    }

    fun certificate(b: ByteArray, off: Int, limit: Int, group: String): List<Fact>? {
        val cert = Der.read(b, off, limit) ?: return null
        if (!cert.isSeq()) return null
        val c = Der.children(b, cert, 5)
        if (c.size < 3 || !c[0].isSeq() || !c[1].isSeq() || c[2].tag != 3) return null
        val t = Der.children(b, c[0], 40)
        var i = 0
        var version = "v1"
        if (t.isNotEmpty() && t[0].cls == 2 && t[0].tag == 0) {
            val vi = Der.children(b, t[0], 2).firstOrNull()
            if (vi != null && vi.tag == 2) version = "v" + (Der.smallInt(b, vi) + 1)
            i = 1
        }
        if (t.size < i + 6) return null
        val serial = t[i]; val sigAlg = t[i + 1]; val issuer = t[i + 2]
        val validity = t[i + 3]; val subject = t[i + 4]; val spkiT = t[i + 5]
        if (serial.tag != 2 || !sigAlg.isSeq() || !issuer.isSeq() || !validity.isSeq() || !subject.isSeq()) return null
        val v = Der.children(b, validity, 3)
        if (v.size < 2) return null
        val facts = ArrayList<Fact>()
        val subj = name(b, subject)
        val iss = name(b, issuer)
        facts.add(Fact("Subject", subj.ifEmpty { "(empty)" }, group))
        facts.add(Fact("Issuer", iss.ifEmpty { "(empty)" }, group))
        var sv = TC.hex(b, serial.vs, minOf(serial.len, 40))
        if (sv.length > 2 && sv.startsWith("00")) sv = sv.substring(2)
        facts.add(Fact("Serial number", sv.ifEmpty { "(empty)" }, group))
        facts.add(Fact("Version", version, group))
        facts.add(Fact("Not before", Der.time(b, v[0]) ?: "unreadable", group))
        facts.add(Fact("Not after", Der.time(b, v[1]) ?: "unreadable", group))
        val key = spki(b, spkiT)
        if (key != null) facts.add(Fact("Public key", key.toString(), group))
        val sa = Der.children(b, c[1], 2).firstOrNull()
        if (sa != null && sa.tag == 6) facts.add(Fact("Signature algorithm", Oids.name(Der.oid(b, sa)), group))
        var isCa: Boolean? = null
        var haveBc = false
        val san = ArrayList<String>()
        var usage: String? = null
        for (e in t.drop(i + 6)) {
            if (e.cls != 2 || e.tag != 3) continue
            val seq = Der.children(b, e, 1).firstOrNull() ?: continue
            for (ext in Der.children(b, seq, 100)) {
                val parts = Der.children(b, ext, 4)
                if (parts.size < 2 || parts[0].tag != 6) continue
                val oct = parts.last()
                if (oct.tag != 4) continue
                when (Der.oid(b, parts[0])) {
                    "2.5.29.19" -> {
                        haveBc = true
                        val bc = Der.read(b, oct.vs, oct.end)
                        isCa = false
                        if (bc != null && bc.isSeq()) {
                            val f = Der.children(b, bc, 3).firstOrNull()
                            if (f != null && f.tag == 1 && f.len == 1) isCa = b[f.vs].toInt() != 0
                        }
                    }
                    "2.5.29.15" -> { val bs = Der.read(b, oct.vs, oct.end); if (bs != null) usage = keyUsage(b, bs) }
                    "2.5.29.17" -> {
                        val gn = Der.read(b, oct.vs, oct.end)
                        if (gn != null && gn.isSeq()) for (n in Der.children(b, gn, 50)) {
                            if (n.cls != 2) continue
                            when (n.tag) {
                                1 -> san.add("email:" + TC.latin1(b, n.vs, n.len))
                                2 -> san.add("DNS:" + TC.latin1(b, n.vs, n.len))
                                6 -> san.add("URI:" + TC.latin1(b, n.vs, n.len))
                                7 -> san.add("IP:" + ipText(b, n))
                            }
                        }
                    }
                }
            }
        }
        if (san.isNotEmpty()) facts.add(Fact("Subject alternative names", san.joinToString(", "), group))
        facts.add(Fact("Is CA", when {
            isCa == true -> "yes"
            haveBc -> "no"
            else -> "no (no basicConstraints extension)"
        }, group))
        if (usage != null) facts.add(Fact("Key usage", usage, group))
        val selfSigned = subject.len == issuer.len &&
            (0 until subject.len).all { b[subject.vs + it] == b[issuer.vs + it] }
        if (selfSigned) facts.add(Fact("Self-issued", "yes (subject equals issuer)", group))
        facts.add(Fact("SHA-256 fingerprint", TC.hex(TC.digest("SHA-256", b, cert.start, cert.end - cert.start), sep = ":"), group))
        return facts
    }
}
