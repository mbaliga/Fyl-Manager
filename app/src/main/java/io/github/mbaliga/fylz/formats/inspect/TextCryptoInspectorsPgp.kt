package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** OpenPGP: ASCII armour block types and (when decodable) the leading packets. */
object PgpInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("asc-pgp", "pgp", "gpg")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source, fileName) }

    private val tagNames = mapOf(
        1 to "Public-key encrypted session key", 2 to "Signature", 3 to "Symmetric-key encrypted session key",
        4 to "One-pass signature", 5 to "Secret key", 6 to "Public key", 7 to "Secret subkey",
        8 to "Compressed data", 9 to "Symmetrically encrypted data", 10 to "Marker", 11 to "Literal data",
        12 to "Trust", 13 to "User ID", 14 to "Public subkey", 17 to "User attribute",
        18 to "Symmetrically encrypted and integrity protected data", 19 to "Modification detection code",
    )
    private val pubAlgs = mapOf(1 to "RSA", 2 to "RSA (encrypt only)", 3 to "RSA (sign only)", 16 to "Elgamal",
        17 to "DSA", 18 to "ECDH", 19 to "ECDSA", 22 to "EdDSA (legacy)")
    private val hashAlgs = mapOf(1 to "MD5", 2 to "SHA-1", 3 to "RIPEMD-160", 8 to "SHA-256", 9 to "SHA-384", 10 to "SHA-512", 11 to "SHA-224")
    private val sigTypes = mapOf(0 to "binary document", 1 to "text document", 0x10 to "generic certification",
        0x11 to "persona certification", 0x12 to "casual certification", 0x13 to "positive certification",
        0x18 to "subkey binding", 0x1F to "direct key", 0x20 to "key revocation")

    private class Pkt(val tag: Int, val off: Int, val len: Int)

    private fun packets(d: ByteArray, max: Int = 50): List<Pkt> {
        val out = ArrayList<Pkt>()
        var p = 0
        while (p < d.size && out.size < max) {
            val h = d[p].toInt() and 0xFF
            if ((h and 0x80) == 0) break
            var tag: Int
            var len: Long
            p++
            if ((h and 0x40) != 0) {
                tag = h and 0x3F
                if (p >= d.size) break
                val l = d[p].toInt() and 0xFF
                when {
                    l < 192 -> { len = l.toLong(); p++ }
                    l < 224 -> { if (p + 1 >= d.size) break; len = (((l - 192) shl 8) + (d[p + 1].toInt() and 0xFF) + 192).toLong(); p += 2 }
                    l == 255 -> { if (p + 4 >= d.size) break; len = Bytes.u32be(d, p + 1); p += 5 }
                    else -> { out.add(Pkt(tag, p + 1, 0)); break } // partial length: stop
                }
            } else {
                tag = (h shr 2) and 0x0F
                when (h and 3) {
                    0 -> { if (p >= d.size) break; len = (d[p].toInt() and 0xFF).toLong(); p += 1 }
                    1 -> { if (p + 1 >= d.size) break; len = Bytes.u16be(d, p).toLong(); p += 2 }
                    2 -> { if (p + 3 >= d.size) break; len = Bytes.u32be(d, p); p += 4 }
                    else -> { out.add(Pkt(tag, p, d.size - p)); break }
                }
            }
            if (len > d.size - p) { out.add(Pkt(tag, p, d.size - p)); break }
            out.add(Pkt(tag, p, len.toInt()))
            p += len.toInt()
        }
        return out
    }

    private fun packetFacts(d: ByteArray, facts: MutableList<Fact>) {
        val pk = packets(d)
        if (pk.isEmpty()) return
        facts.add(Fact("Packets (first ${pk.size})", pk.joinToString(", ") { tagNames[it.tag] ?: "tag ${it.tag}" }, "OpenPGP packets"))
        var gotFp = false
        for (p in pk) {
            val g = "OpenPGP packets"
            when (p.tag) {
                13 -> facts.add(Fact("User ID", TC.cap(String(d, p.off, minOf(p.len, 300), Charsets.UTF_8), 200), g))
                6, 14, 5, 7 -> if (p.len >= 6 && (d[p.off].toInt() and 0xFF) == 4) {
                    val created = TC.fmtEpoch(Bytes.u32be(d, p.off + 1))
                    val alg = d[p.off + 5].toInt() and 0xFF
                    var desc = "v4 ${pubAlgs[alg] ?: "algorithm $alg"}"
                    if ((alg in 1..3 || alg == 16 || alg == 17) && p.len >= 8) desc += " ${Bytes.u16be(d, p.off + 6)} bits"
                    facts.add(Fact(tagNames[p.tag] ?: "Key", "$desc, created $created", g))
                    if (p.tag == 6 && !gotFp) {
                        gotFp = true
                        val buf = ByteArray(3 + p.len)
                        buf[0] = 0x99.toByte(); buf[1] = (p.len shr 8).toByte(); buf[2] = p.len.toByte()
                        System.arraycopy(d, p.off, buf, 3, p.len)
                        val fp = TC.hex(TC.digest("SHA-1", buf, 0, buf.size))
                        facts.add(Fact("Fingerprint (v4)", fp.uppercase(), g))
                        facts.add(Fact("Key ID", fp.takeLast(16).uppercase(), g))
                    }
                }
                2 -> if (p.len >= 4 && (d[p.off].toInt() and 0xFF) == 4) {
                    val type = d[p.off + 1].toInt() and 0xFF
                    val pa = d[p.off + 2].toInt() and 0xFF
                    val ha = d[p.off + 3].toInt() and 0xFF
                    facts.add(Fact("Signature", "v4 ${sigTypes[type] ?: "type 0x" + Integer.toHexString(type)}, ${pubAlgs[pa] ?: "algorithm $pa"} with ${hashAlgs[ha] ?: "hash $ha"}", g))
                }
            }
        }
    }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val data = TC.readUpTo(source, 4 shl 20)
        if (data.isEmpty()) return null
        val id = TC.idFor(fileName, setOf("asc", "pgp", "gpg"), "asc")
        val truncated = TC.truncated(source, data)
        val text = String(data, Charsets.ISO_8859_1)
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        if (text.contains("-----BEGIN PGP ")) {
            val lines = text.split('\n').take(200_000).map { it.trim() }
            var blocks = 0
            val labels = ArrayList<String>()
            var i = 0
            var firstBody: ByteArray? = null
            while (i < lines.size && blocks < 100) {
                val l = lines[i]
                if (l.startsWith("-----BEGIN PGP ") && l.endsWith("-----")) {
                    val label = l.substring(15, l.length - 5)
                    blocks++; labels.add(label)
                    val g = "Block $blocks ($label)"
                    var j = i + 1
                    while (j < lines.size && lines[j].isNotEmpty()) {
                        val h = lines[j]
                        val k = h.indexOf(':')
                        if (k > 0 && !h.startsWith("-----") && facts.size < 200) facts.add(Fact(h.substring(0, k), TC.cap(h.substring(k + 1).trim(), 200), g))
                        if (h.startsWith("-----")) break
                        j++
                    }
                    if (label == "SIGNED MESSAGE") { i = j; continue }
                    val body = StringBuilder()
                    j++
                    var crc: String? = null
                    var closed = false
                    while (j < lines.size) {
                        val b = lines[j]
                        if (b.startsWith("-----END PGP")) { closed = true; break }
                        if (b.startsWith("=") && b.length == 5) crc = b.substring(1)
                        else if (body.length < 6 * 1024 * 1024) body.append(b)
                        j++
                    }
                    if (!closed && !truncated) warnings.add("Block $blocks ($label) has no END line")
                    val dec = TC.base64(body.toString())
                    if (dec != null) {
                        facts.add(Fact("Decoded size", Bytes.humanSize(dec.size.toLong()), g))
                        if (firstBody == null) firstBody = dec
                    } else warnings.add("Block $blocks ($label): armour body is not valid base64")
                    if (crc != null) facts.add(Fact("CRC-24 present", "yes", g))
                    i = j
                }
                i++
            }
            facts.add(0, Fact("Blocks", labels.joinToString(", ") { it.lowercase() }, "Armour"))
            firstBody?.let { packetFacts(it, facts) }
            val title = "PGP " + (labels.firstOrNull()?.lowercase() ?: "data")
            return InspectionResult(id, title, "${labels.size} armoured block${if (labels.size == 1) "" else "s"}", facts, warnings)
        }
        if ((data[0].toInt() and 0x80) != 0) {
            val tagByte = data[0].toInt() and 0xFF
            val tag = if ((tagByte and 0x40) != 0) tagByte and 0x3F else (tagByte shr 2) and 0x0F
            if (tag !in tagNames) return null
            packetFacts(data, facts)
            if (facts.isEmpty()) return null
            if (truncated) warnings.add("File larger than the read limit; only the first packets were examined")
            return InspectionResult(id, "OpenPGP binary data", tagNames[tag], facts, warnings)
        }
        return null
    }
}
