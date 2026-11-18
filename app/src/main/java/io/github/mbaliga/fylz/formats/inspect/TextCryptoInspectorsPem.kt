package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

internal class PemBlock(val label: String, val headers: List<String>, val body: ByteArray?, val b64Chars: Int)

internal object Pem {
    fun parse(text: String, maxBlocks: Int = 200): List<PemBlock> {
        val blocks = ArrayList<PemBlock>()
        var pos = 0
        var lineCount = 0
        var label: String? = null
        var headers = ArrayList<String>()
        val body = StringBuilder()
        var prevCont = false
        while (pos < text.length && blocks.size < maxBlocks && lineCount < 500_000) {
            var eol = text.indexOf('\n', pos)
            if (eol < 0) eol = text.length
            val line = text.substring(pos, eol).trim()
            pos = eol + 1
            lineCount++
            if (label == null) {
                if (line.startsWith("-----BEGIN ") && line.endsWith("-----") && line.length > 16) {
                    label = line.substring(11, line.length - 5); headers = ArrayList(); body.setLength(0); prevCont = false
                }
                continue
            }
            if (line.startsWith("-----END ")) {
                val decoded = TC.base64(body.toString())
                blocks.add(PemBlock(label, headers, decoded, body.length))
                label = null
                continue
            }
            if (line.contains(':') || prevCont) {
                if (headers.size < 20) headers.add(line)
                prevCont = line.endsWith("\\")
            } else if (body.length < 6 * 1024 * 1024) body.append(line)
        }
        return blocks
    }

    fun keyFacts(label: String, b: ByteArray, group: String, warnings: MutableList<String>): List<Fact> {
        val out = ArrayList<Fact>()
        val top = Der.read(b, 0) ?: return out
        val c = Der.children(b, top, 20)
        when (label) {
            "CERTIFICATE", "X509 CERTIFICATE", "TRUSTED CERTIFICATE" -> {
                val f = X509.certificate(b, 0, b.size, group)
                if (f == null) warnings.add("$label block does not parse as an X.509 certificate") else out.addAll(f)
            }
            "PUBLIC KEY" -> X509.spki(b, top)?.let { out.add(Fact("Public key", it.toString(), group)) }
            "RSA PUBLIC KEY" -> if (c.isNotEmpty() && c[0].tag == 2) out.add(Fact("Public key", "RSA ${TC.bitLen(b, c[0].vs, c[0].len)} bits", group))
            "RSA PRIVATE KEY" -> if (c.size >= 2 && c[1].tag == 2) out.add(Fact("Key", "RSA ${TC.bitLen(b, c[1].vs, c[1].len)} bits (private)", group))
            "DSA PRIVATE KEY" -> if (c.size >= 2 && c[1].tag == 2) out.add(Fact("Key", "DSA ${TC.bitLen(b, c[1].vs, c[1].len)} bits (private)", group))
            "EC PRIVATE KEY" -> {
                val params = c.firstOrNull { it.cls == 2 && it.tag == 0 }
                val oid = params?.let { Der.children(b, it, 1).firstOrNull() }?.takeIf { it.tag == 6 }?.let { Der.oid(b, it) }
                val cv = oid?.let { Oids.curve(it) }
                out.add(Fact("Key", if (cv != null) "EC ${cv.second} bits (${cv.first}, private)" else "EC (private)" + (if (oid != null) ", curve $oid" else ""), group))
            }
            "PRIVATE KEY" -> {
                if (c.size >= 3 && c[1].isSeq()) {
                    val alg = Der.children(b, c[1], 3)
                    val oid = alg.firstOrNull()?.takeIf { it.tag == 6 }?.let { Der.oid(b, it) }
                    when (oid) {
                        "1.2.840.113549.1.1.1" -> {
                            val inner = if (c[2].tag == 4) Der.read(b, c[2].vs, c[2].end) else null
                            val ic = inner?.let { Der.children(b, it, 3) }
                            val bits = if (ic != null && ic.size >= 2 && ic[1].tag == 2) TC.bitLen(b, ic[1].vs, ic[1].len) else null
                            out.add(Fact("Key", "RSA" + (if (bits != null) " $bits bits" else "") + " (private, PKCS#8)", group))
                        }
                        "1.2.840.10045.2.1" -> {
                            val curve = alg.getOrNull(1)?.takeIf { it.tag == 6 }?.let { Der.oid(b, it) }
                            val cv = curve?.let { Oids.curve(it) }
                            out.add(Fact("Key", if (cv != null) "EC ${cv.second} bits (${cv.first}, private, PKCS#8)" else "EC (private, PKCS#8)", group))
                        }
                        null -> {}
                        else -> out.add(Fact("Key", "${Oids.name(oid)} (private, PKCS#8)", group))
                    }
                }
            }
            "ENCRYPTED PRIVATE KEY" -> out.add(Fact("Key", "Encrypted PKCS#8 private key", group))
            "CERTIFICATE REQUEST", "NEW CERTIFICATE REQUEST" -> {
                val info = c.firstOrNull()
                if (info != null && info.isSeq()) {
                    val ic = Der.children(b, info, 6)
                    if (ic.size >= 3 && ic[1].isSeq()) {
                        out.add(Fact("Subject", X509.name(b, ic[1]).ifEmpty { "(empty)" }, group))
                        X509.spki(b, ic[2])?.let { out.add(Fact("Public key", it.toString(), group)) }
                    }
                }
                val sa = c.getOrNull(1)?.let { Der.children(b, it, 2).firstOrNull() }
                if (sa != null && sa.tag == 6) out.add(Fact("Signature algorithm", Oids.name(Der.oid(b, sa)), group))
            }
            "X509 CRL" -> {
                val tbs = c.firstOrNull()
                if (tbs != null && tbs.isSeq()) {
                    val t = Der.children(b, tbs, 8)
                    var i = 0
                    if (t.isNotEmpty() && t[0].tag == 2) i = 1
                    val issuer = t.getOrNull(i + 1)
                    if (issuer != null && issuer.isSeq()) out.add(Fact("Issuer", X509.name(b, issuer), group))
                    t.getOrNull(i + 2)?.let { Der.time(b, it) }?.let { out.add(Fact("This update", it, group)) }
                }
            }
            "DH PARAMETERS" -> if (c.isNotEmpty() && c[0].tag == 2) out.add(Fact("Prime", "${TC.bitLen(b, c[0].vs, c[0].len)} bits", group))
        }
        return out
    }
}

/** PEM text containers: certificates, keys, requests, PGP-free armour. */
object PemInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("pem")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source) }

    fun inspectText(text: String, formatId: String, truncated: Boolean): InspectionResult? {
        val blocks = Pem.parse(text)
        if (blocks.isEmpty()) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        val counts = LinkedHashMap<String, Int>()
        for (b in blocks) counts[b.label] = (counts[b.label] ?: 0) + 1
        facts.add(Fact("Blocks", blocks.size.toString(), "Overview"))
        for ((l, n) in counts) facts.add(Fact(l, "x$n", "Block labels"))
        if (truncated) warnings.add("File larger than the read limit; only the first part was examined")
        for ((i, b) in blocks.take(10).withIndex()) {
            val g = "Block ${i + 1} (${b.label})"
            val encrypted = b.headers.any { it.contains("ENCRYPTED", true) }
            if (encrypted) {
                val dek = b.headers.firstOrNull { it.startsWith("DEK-Info:", true) }?.substringAfter(':')?.trim()?.substringBefore(',')
                facts.add(Fact("Encrypted", "yes" + (if (dek != null) " ($dek)" else ""), g))
            }
            val d = b.body
            if (d == null) { warnings.add("Block ${i + 1} (${b.label}): body is not valid base64"); continue }
            facts.add(Fact("Decoded size", Bytes.humanSize(d.size.toLong()), g))
            if (encrypted) continue
            if (b.label == "OPENSSH PRIVATE KEY") facts.addAll(Ssh.privateFacts(d, g, warnings))
            else if (b.label == "SSH2 PUBLIC KEY") facts.addAll(Ssh.blobFacts(d, g, b.headers.firstOrNull { it.startsWith("Comment:", true) }?.substringAfter(':')?.trim()?.trim('"')))
            else facts.addAll(Pem.keyFacts(b.label, d, g, warnings))
        }
        if (blocks.size > 10) warnings.add("Only the first 10 of ${blocks.size} blocks are described")
        val title = if (counts.size == 1) counts.keys.first().lowercase().replaceFirstChar { it.uppercase() } else "PEM bundle"
        return InspectionResult(formatId, title, counts.entries.joinToString(", ") { "${it.value} ${it.key}" }, facts, warnings)
    }

    private fun run(source: ByteSource): InspectionResult? {
        val data = TC.readUpTo(source, FormatInspector.MAX_READ_BYTES)
        return inspectText(String(data, Charsets.ISO_8859_1), "pem", TC.truncated(source, data))
    }
}

/** DER certificates and other ASN.1 containers (.der .crt .cer .p12 .pfx); PEM-encoded .crt/.cer are accepted too. */
object DerInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("der", "crt", "cer", "p12")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val b = TC.readUpTo(source, 4 shl 20)
        if (b.isEmpty()) return null
        val id = TC.idFor(fileName, formatIds + "pfx", "der").let { if (it == "pfx") "p12" else it }
        if (Bytes.startsWith(b, "-----BEGIN") || (b.size > 3 && b[0].toInt() == 0xEF && Bytes.startsWith(b, "-----BEGIN", 3))) {
            return PemInspector.inspectText(String(b, Charsets.ISO_8859_1), id, TC.truncated(source, b))
        }
        val top = Der.read(b, 0) ?: return null
        if (!top.isSeq()) return null
        val warnings = ArrayList<String>()
        if (top.end < b.size && !TC.truncated(source, b)) warnings.add("${b.size - top.end} trailing bytes after the ASN.1 structure")
        val c = Der.children(b, top, 10)
        val g = "Certificate"
        val cert = X509.certificate(b, 0, b.size, g)
        if (cert != null) {
            val subj = cert.firstOrNull { it.label == "Subject" }?.value ?: ""
            return InspectionResult(id, "X.509 certificate", subj, cert, warnings)
        }
        // PKCS#12: SEQUENCE { INTEGER 3, SEQUENCE { OID data|signedData, ... }, [MAC] }
        if (c.size >= 2 && c[0].tag == 2 && Der.smallInt(b, c[0]) == 3 && c[1].isSeq()) {
            val oid = Der.children(b, c[1], 2).firstOrNull()?.takeIf { it.tag == 6 }?.let { Der.oid(b, it) }
            if (oid == "1.2.840.113549.1.7.1" || oid == "1.2.840.113549.1.7.2") {
                val facts = listOf(
                    Fact("Container", "PKCS#12 / PFX (version 3)", "PKCS#12"),
                    Fact("Contents", "Not inspected: keys and certificates are protected by a password", "PKCS#12"),
                    Fact("Has MAC", if (c.size >= 3 && c[2].isSeq()) "yes" else "no", "PKCS#12"),
                )
                return InspectionResult(id, "PKCS#12 key store", "Password-protected bundle", facts, warnings)
            }
        }
        // PKCS#7 / CMS: SEQUENCE { OID signedData, [0] ... }
        val first = c.firstOrNull()
        if (first != null && first.tag == 6) {
            val oid = Der.oid(b, first)
            val name = when (oid) {
                "1.2.840.113549.1.7.2" -> "PKCS#7 / CMS signed data"
                "1.2.840.113549.1.7.3" -> "PKCS#7 / CMS enveloped data"
                else -> null
            }
            if (name != null) return InspectionResult(id, name, null, listOf(Fact("Content type", "$name ($oid)", "ASN.1")), warnings)
        }
        // Private key / public key / CSR
        val keyFacts = ArrayList<Fact>()
        X509.spki(b, top)?.let { keyFacts.add(Fact("Public key", it.toString(), "Key")) }
        if (keyFacts.isEmpty() && c.size >= 3 && c[0].tag == 2 && c[1].isSeq() && c[2].tag == 4) {
            keyFacts.addAll(Pem.keyFacts("PRIVATE KEY", b, "Key", warnings))
        }
        if (keyFacts.isEmpty() && c.size >= 3 && c[0].isSeq() && c[1].isSeq() && c[2].tag == 3) {
            keyFacts.addAll(Pem.keyFacts("CERTIFICATE REQUEST", b, "Request", warnings))
            if (keyFacts.isNotEmpty()) return InspectionResult(id, "PKCS#10 certificate request", null, keyFacts, warnings)
        }
        if (keyFacts.isNotEmpty()) return InspectionResult(id, "ASN.1 key", keyFacts.first().value, keyFacts, warnings)
        val facts = listOf(
            Fact("Structure", "ASN.1 SEQUENCE with ${c.size} element${if (c.size == 1) "" else "s"}", "ASN.1"),
            Fact("Length", Bytes.humanSize(top.len.toLong()), "ASN.1"),
        )
        warnings.add("Recognised as DER but not as a certificate, key or request")
        return InspectionResult(id, "ASN.1 DER data", null, facts, warnings)
    }
}
