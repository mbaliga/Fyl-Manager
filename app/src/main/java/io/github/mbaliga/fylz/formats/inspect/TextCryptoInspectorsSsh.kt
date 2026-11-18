package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** OpenSSH key blobs: public key lines, RFC 4716 blocks and openssh-key-v1 private containers. */
internal object Ssh {
    /** Reads a uint32-length-prefixed string at [pos]; returns (offset, length) or null. */
    class Rd(val b: ByteArray, var pos: Int) {
        fun u32(): Long? { if (pos + 4 > b.size) return null; val v = Bytes.u32be(b, pos); pos += 4; return v }
        fun str(): Pair<Int, Int>? {
            val l = u32() ?: return null
            if (l > b.size - pos) return null
            val r = pos to l.toInt(); pos += l.toInt(); return r
        }
    }

    fun describe(blob: ByteArray): Pair<String, String?>? {
        val r = Rd(blob, 0)
        val t = r.str() ?: return null
        val type = TC.latin1(blob, t.first, t.second)
        return when {
            type == "ssh-rsa" -> {
                r.str() ?: return type to null
                val n = r.str() ?: return type to null
                type to "${TC.bitLen(blob, n.first, n.second)} bits"
            }
            type == "ssh-ed25519" || type == "sk-ssh-ed25519@openssh.com" -> type to "256 bits"
            type.startsWith("ecdsa-sha2-nistp") -> type to (type.removePrefix("ecdsa-sha2-nistp").substringBefore('@') + " bits")
            type == "sk-ecdsa-sha2-nistp256@openssh.com" -> type to "256 bits"
            type == "ssh-dss" -> {
                val p = r.str() ?: return type to null
                type to "${TC.bitLen(blob, p.first, p.second)} bits"
            }
            else -> type to null
        }
    }

    fun fingerprint(blob: ByteArray): String = "SHA256:" + TC.base64NoPad(TC.digest("SHA-256", blob, 0, blob.size))

    fun blobFacts(blob: ByteArray, group: String, comment: String?): List<Fact> {
        val out = ArrayList<Fact>()
        val d = describe(blob)
        if (d != null) {
            out.add(Fact("Key type", d.first, group))
            if (d.second != null) out.add(Fact("Key size", d.second!!, group))
        }
        out.add(Fact("SHA-256 fingerprint", fingerprint(blob), group))
        if (!comment.isNullOrEmpty()) out.add(Fact("Comment", TC.cap(comment, 200), group))
        return out
    }

    private val keyLine = Regex("(?:^|\\s)(ssh-rsa|ssh-ed25519|ssh-dss|ecdsa-sha2-nistp(?:256|384|521)|sk-ssh-ed25519@openssh\\.com|sk-ecdsa-sha2-nistp256@openssh\\.com)\\s+([A-Za-z0-9+/=]+)(?:\\s+(.*))?$")

    fun publicLine(line: String): Triple<String, ByteArray, String?>? {
        val m = keyLine.find(line.trim()) ?: return null
        val blob = TC.base64(m.groupValues[2]) ?: return null
        return Triple(m.groupValues[1], blob, m.groupValues[3].trim().ifEmpty { null })
    }

    /** Facts from the decoded body of an "OPENSSH PRIVATE KEY" block. Never reveals key material. */
    fun privateFacts(d: ByteArray, group: String, warnings: MutableList<String>): List<Fact> {
        val magic = "openssh-key-v1\u0000"
        if (d.size < magic.length || TC.latin1(d, 0, magic.length) != magic) { warnings.add("Not an openssh-key-v1 container"); return emptyList() }
        val out = ArrayList<Fact>()
        val r = Rd(d, magic.length)
        val cipher = r.str() ?: return out
        val kdf = r.str() ?: return out
        r.str() ?: return out
        val n = r.u32() ?: return out
        val cipherName = TC.latin1(d, cipher.first, cipher.second)
        out.add(Fact("Cipher", cipherName, group))
        out.add(Fact("KDF", TC.latin1(d, kdf.first, kdf.second), group))
        out.add(Fact("Keys in file", n.toString(), group))
        out.add(Fact("Encrypted", if (cipherName == "none") "no" else "yes (passphrase protected)", group))
        if (n < 1 || n > 100) return out
        val pubs = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n.toInt()) { pubs.add(r.str() ?: return out) }
        val first = d.copyOfRange(pubs[0].first, pubs[0].first + pubs[0].second)
        out.addAll(blobFacts(first, group, null))
        if (cipherName == "none") {
            val priv = r.str() ?: return out
            val p = Rd(d.copyOfRange(priv.first, priv.first + priv.second), 0)
            val c1 = p.u32(); val c2 = p.u32()
            if (c1 == null || c2 == null) return out
            if (c1 != c2) { warnings.add("Private section check integers differ; the file may be corrupt"); return out }
            val type = p.str()?.let { TC.latin1(p.b, it.first, it.second) } ?: return out
            val fields = when {
                type == "ssh-ed25519" -> 2
                type == "ssh-rsa" -> 6
                type.startsWith("ecdsa-sha2-") -> 3
                else -> return out
            }
            for (k in 0 until fields) p.str() ?: return out
            val cm = p.str() ?: return out
            val comment = TC.latin1(p.b, cm.first, minOf(cm.second, 500))
            if (comment.isNotEmpty()) out.add(Fact("Comment", comment, group))
        }
        return out
    }
}

/** OpenSSH public keys (.pub, authorized_keys-style lines). */
object SshPublicKeyInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("pub")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source) }

    private fun run(source: ByteSource): InspectionResult? {
        val data = TC.readUpTo(source, 256 * 1024)
        val text = TC.utf8(data)
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(1000)
        val keys = lines.mapNotNull { Ssh.publicLine(it) }
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        if (keys.isEmpty()) {
            // RFC 4716 form or an OpenSSH private key are handled by the PEM inspector.
            return null
        }
        for ((i, k) in keys.take(20).withIndex()) {
            val g = if (keys.size == 1) "Key" else "Key ${i + 1}"
            val bf = Ssh.blobFacts(k.second, g, k.third)
            facts.addAll(bf)
            val declared = k.first
            val actual = Ssh.describe(k.second)?.first
            if (actual != null && actual != declared) warnings.add("Key ${i + 1}: line says $declared but the key data says $actual")
        }
        if (keys.size > 20) warnings.add("Only the first 20 of ${keys.size} keys are listed")
        val first = Ssh.describe(keys[0].second)
        return InspectionResult("pub", "OpenSSH public key" + if (keys.size > 1) "s" else "",
            if (keys.size == 1) listOfNotNull(first?.first, first?.second).joinToString(" ") else "${keys.size} keys",
            facts, warnings)
    }
}
