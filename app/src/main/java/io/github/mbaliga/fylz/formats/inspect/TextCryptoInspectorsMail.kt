package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.nio.charset.Charset

internal class MailHeaders(val list: List<Pair<String, String>>) {
    fun get(name: String): String? = list.firstOrNull { it.first == name }?.second
    fun all(name: String): List<String> = list.filter { it.first == name }.map { it.second }
}

internal class MimePart(
    val path: String, val type: String, val name: String?, val disposition: String?,
    val size: Long, val charset: String?, val encoding: String?, val bodyStart: Int, val bodyEnd: Int,
)

internal object Mime {
    private val encWord = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?\\s]*)\\?=")

    private fun charsetOf(name: String): Charset = try {
        Charset.forName(name.substringBefore('*'))
    } catch (t: Throwable) { Charsets.ISO_8859_1 }

    private fun qDecode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '_') { out.write(0x20); i++ }
            else if (c == '=' && i + 2 < s.length + 0 && hexVal(s[i + 1]) >= 0 && hexVal(s[i + 2]) >= 0) {
                out.write(hexVal(s[i + 1]) * 16 + hexVal(s[i + 2])); i += 3
            } else { out.write(c.code and 0xFF); i++ }
        }
        return out.toByteArray()
    }

    fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** RFC 2047 decoding; adjacent encoded words of one charset are decoded together. */
    fun decodeWords(s: String): String {
        if (!s.contains("=?")) return s
        val sb = StringBuilder()
        var last = 0
        var pendingCs: String? = null
        val pending = java.io.ByteArrayOutputStream()
        fun flush() {
            if (pendingCs != null) {
                sb.append(String(pending.toByteArray(), charsetOf(pendingCs!!)))
                pending.reset(); pendingCs = null
            }
        }
        var count = 0
        for (m in encWord.findAll(s)) {
            if (++count > 500) break
            val gap = s.substring(last, m.range.first)
            if (!(pendingCs != null && gap.isBlank())) { flush(); sb.append(gap) }
            val cs = m.groupValues[1].substringBefore('*').lowercase()
            val bytes = if (m.groupValues[2].equals("b", true)) (TC.base64(m.groupValues[3]) ?: ByteArray(0)) else qDecode(m.groupValues[3])
            if (pendingCs != null && pendingCs != cs) flush()
            pendingCs = cs
            pending.write(bytes)
            last = m.range.last + 1
        }
        flush()
        sb.append(s.substring(last))
        return sb.toString()
    }

    fun parseHeaders(s: String, start: Int, limit: Int): Pair<MailHeaders, Int> {
        val list = ArrayList<Pair<String, String>>()
        var pos = start
        var curName: String? = null
        val cur = StringBuilder()
        fun commit() {
            val n = curName
            if (n != null && list.size < 500) list.add(n to cur.toString().trim())
            curName = null; cur.setLength(0)
        }
        while (pos < limit) {
            var eol = s.indexOf('\n', pos)
            if (eol < 0 || eol >= limit) eol = limit
            var lineEnd = eol
            if (lineEnd > pos && s[lineEnd - 1] == '\r') lineEnd--
            if (lineEnd == pos) { commit(); pos = minOf(eol + 1, limit); return MailHeaders(list) to pos }
            val first = s[pos]
            if ((first == ' ' || first == '\t') && curName != null) {
                if (cur.length < 20000) cur.append(' ').append(s, pos, lineEnd)
            } else {
                commit()
                var colon = pos
                while (colon < lineEnd && s[colon] != ':') colon++
                if (colon in (pos + 1) until lineEnd) {
                    curName = s.substring(pos, colon).trim().lowercase()
                    cur.append(s, colon + 1, lineEnd)
                }
            }
            pos = eol + 1
        }
        commit()
        return MailHeaders(list) to limit
    }

    /** Splits "main; k=v; k2=\"v2\"" into the main value and a parameter map (RFC 2231 aware). */
    fun parseParams(value: String): Pair<String, Map<String, String>> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var q = false
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && q && i + 1 < value.length) { cur.append(c).append(value[i + 1]); i += 2; continue }
            if (c == '"') q = !q
            if (c == ';' && !q) { parts.add(cur.toString()); cur.setLength(0) } else cur.append(c)
            i++
        }
        parts.add(cur.toString())
        val main = parts[0].trim()
        val raw = LinkedHashMap<String, String>()
        for (p in parts.drop(1).take(50)) {
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            var v = p.substring(eq + 1).trim()
            if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1).replace(Regex("\\\\(.)"), "$1")
            raw[p.substring(0, eq).trim().lowercase()] = v
        }
        val out = LinkedHashMap<String, String>()
        val cont = HashMap<String, java.util.TreeMap<Int, Pair<String, Boolean>>>()
        for ((k, v) in raw) {
            val m = Regex("^(.+)\\*(\\d+)(\\*)?$").find(k)
            if (m != null) {
                cont.getOrPut(m.groupValues[1]) { java.util.TreeMap() }[m.groupValues[2].toInt()] = v to m.groupValues[3].isNotEmpty()
            } else if (k.endsWith("*")) {
                out[k.dropLast(1)] = decodeExt(v)
            } else out[k] = v
        }
        for ((base, parts2) in cont) {
            val first = parts2.firstEntry()
            var cs: String? = null
            val bytes = java.io.ByteArrayOutputStream()
            var plain = StringBuilder()
            for ((idx, pe) in parts2) {
                var v = pe.first
                if (pe.second) {
                    if (idx == first.key && v.contains("'")) { cs = v.substringBefore('\''); v = v.substringAfter('\'').substringAfter('\'') }
                    bytes.write(pctBytes(v))
                } else bytes.write(v.toByteArray(Charsets.ISO_8859_1))
            }
            out[base] = String(bytes.toByteArray(), if (cs != null) charsetOf(cs) else Charsets.UTF_8)
        }
        return main to out
    }

    private fun pctBytes(v: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < v.length) {
            if (v[i] == '%' && i + 2 < v.length + 0 && hexVal(v[i + 1]) >= 0 && hexVal(v[i + 2]) >= 0) {
                o.write(hexVal(v[i + 1]) * 16 + hexVal(v[i + 2])); i += 3
            } else { o.write(v[i].code and 0xFF); i++ }
        }
        return o.toByteArray()
    }

    private fun decodeExt(v: String): String {
        val cs = if (v.contains("'")) v.substringBefore('\'') else null
        val data = if (v.contains("'")) v.substringAfter('\'').substringAfter('\'') else v
        return String(pctBytes(data), if (cs != null && cs.isNotEmpty()) charsetOf(cs) else Charsets.UTF_8)
    }

    fun decodedSize(s: String, start: Int, end: Int, enc: String?): Long {
        val e = enc?.trim()?.lowercase()
        if (e == "base64") {
            var n = 0L
            for (i in start until end) { val c = s[i]; if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/') n++ }
            return (n / 4) * 3 + when ((n % 4).toInt()) { 2 -> 1; 3 -> 2; else -> 0 }
        }
        if (e == "quoted-printable") {
            var n = 0L
            var i = start
            while (i < end) {
                if (s[i] == '=') {
                    if (i + 2 < end + 0 && hexVal(s[i + 1]) >= 0 && hexVal(s[i + 2]) >= 0) { n++; i += 3; continue }
                    if (i + 1 < end && s[i + 1] == '\n') { i += 2; continue }
                    if (i + 2 < end && s[i + 1] == '\r' && s[i + 2] == '\n') { i += 3; continue }
                }
                n++; i++
            }
            return n
        }
        return (end - start).toLong()
    }

    fun splitMultipart(s: String, bs: Int, be: Int, boundary: String): List<IntRange> {
        val delim = "--$boundary"
        val out = ArrayList<IntRange>()
        var partStart = -1
        var pos = bs
        while (pos < be && out.size < 200) {
            var eol = s.indexOf('\n', pos)
            if (eol < 0 || eol >= be) eol = be
            if (s.startsWith(delim, pos)) {
                val rest = s.substring(pos + delim.length, minOf(eol, pos + delim.length + 8)).trimEnd('\r', ' ', '\t')
                val closing = rest == "--"
                if (rest.isEmpty() || closing) {
                    if (partStart >= 0) {
                        var e = pos
                        if (e > partStart && s[e - 1] == '\n') e--
                        if (e > partStart && s[e - 1] == '\r') e--
                        out.add(partStart until maxOf(e, partStart))
                    }
                    if (closing) return out
                    partStart = minOf(eol + 1, be)
                }
            }
            pos = eol + 1
        }
        if (partStart in 0 until be && out.size < 200) out.add(partStart until be)
        return out
    }

    fun walk(s: String, start: Int, end: Int, depth: Int, path: String, out: MutableList<MimePart>) {
        if (out.size >= 200) return
        val (h, bodyStart) = parseHeaders(s, start, end)
        val ctRaw = h.get("content-type") ?: "text/plain"
        val (type0, params) = parseParams(ctRaw)
        val type = type0.lowercase().ifEmpty { "text/plain" }
        val boundary = params["boundary"]
        if (type.startsWith("multipart/") && !boundary.isNullOrEmpty() && depth < 8) {
            val ranges = splitMultipart(s, bodyStart, end, boundary)
            for ((i, r) in ranges.withIndex()) walk(s, r.first, r.last + 1, depth + 1, "$path.${i + 1}", out)
            return
        }
        val dispRaw = h.get("content-disposition")
        val (disp, dparams) = if (dispRaw != null) parseParams(dispRaw) else ("" to emptyMap())
        val name = (dparams["filename"] ?: params["name"])?.let { decodeWords(it) }
        val enc = h.get("content-transfer-encoding")?.trim()?.lowercase()
        out.add(MimePart(path, type, name, disp.lowercase().ifEmpty { null }, decodedSize(s, bodyStart, end, enc),
            params["charset"], enc, bodyStart, end))
    }

    fun decodeBody(s: String, p: MimePart, maxChars: Int): String {
        val end = minOf(p.bodyEnd, p.bodyStart + maxChars * 2 + 64)
        val raw = s.substring(p.bodyStart, maxOf(p.bodyStart, end))
        val bytes: ByteArray = when (p.encoding) {
            "base64" -> TC.base64(raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' }.let { it.substring(0, it.length - it.length % 4) }) ?: ByteArray(0)
            "quoted-printable" -> {
                val o = java.io.ByteArrayOutputStream()
                var i = 0
                while (i < raw.length) {
                    val c = raw[i]
                    if (c == '=' && i + 2 < raw.length + 0 && hexVal(raw[i + 1]) >= 0 && hexVal(raw[i + 2]) >= 0) { o.write(hexVal(raw[i + 1]) * 16 + hexVal(raw[i + 2])); i += 3 }
                    else if (c == '=' && i + 1 < raw.length && raw[i + 1] == '\n') i += 2
                    else if (c == '=' && i + 2 < raw.length && raw[i + 1] == '\r' && raw[i + 2] == '\n') i += 3
                    else { o.write(c.code and 0xFF); i++ }
                }
                o.toByteArray()
            }
            else -> raw.toByteArray(Charsets.ISO_8859_1)
        }
        val cs = if (p.charset != null) charsetOf(p.charset) else Charsets.UTF_8
        return String(bytes, cs).take(maxChars)
    }
}

/** A single RFC 5322 / MIME e-mail message (.eml). */
object EmlInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("eml")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source) }

    private fun run(source: ByteSource): InspectionResult? {
        val data = TC.readUpTo(source, FormatInspector.MAX_READ_BYTES)
        if (data.isEmpty()) return null
        val s = String(data, Charsets.ISO_8859_1)
        val (h, bodyStart) = Mime.parseHeaders(s, 0, s.length)
        if (h.list.isEmpty()) return null
        val known = setOf("from", "to", "subject", "date", "message-id", "mime-version", "received", "content-type", "return-path")
        if (h.list.none { it.first in known }) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        if (TC.truncated(source, data)) warnings.add("File larger than the read limit; only the first ${Bytes.humanSize(data.size.toLong())} was examined")
        fun dec(n: String) = h.get(n)?.let { Mime.decodeWords(it) }
        val subject = dec("subject")
        for ((label, key) in listOf("From" to "from", "To" to "to", "Cc" to "cc", "Bcc" to "bcc", "Reply-To" to "reply-to",
            "Subject" to "subject", "Date" to "date", "Message-ID" to "message-id", "In-Reply-To" to "in-reply-to",
            "User-Agent" to "user-agent", "X-Mailer" to "x-mailer")) {
            val v = dec(key)
            if (v != null) facts.add(Fact(label, TC.cap(v, 500), "Headers"))
        }
        val hops = h.all("received").size
        if (hops > 0) facts.add(Fact("Received hops", hops.toString(), "Headers"))
        h.get("mime-version")?.let { facts.add(Fact("MIME-Version", it, "Headers")) }
        val parts = ArrayList<MimePart>()
        if (h.get("content-type") != null || h.get("mime-version") != null) Mime.walk(s, 0, s.length, 0, "1", parts)
        else parts.add(MimePart("1", "text/plain", null, null, (s.length - bodyStart).toLong(), null, null, bodyStart, s.length))
        facts.add(Fact("Parts", parts.size.toString(), "Structure"))
        val attachments = parts.filter { it.disposition == "attachment" || (it.name != null && it.disposition != "inline") || (it.name != null && !it.type.startsWith("text/")) }
        facts.add(Fact("Attachments", attachments.size.toString(), "Structure"))
        for (p in parts.take(40)) {
            val d = StringBuilder(p.type)
            if (p.name != null) d.append(", \"").append(TC.cap(p.name, 120)).append("\"")
            if (p.disposition != null) d.append(", ").append(p.disposition)
            if (p.charset != null) d.append(", charset ").append(p.charset)
            d.append(", ").append(Bytes.humanSize(p.size))
            facts.add(Fact("Part ${p.path}", d.toString(), "Structure"))
        }
        val body = parts.firstOrNull { it.type == "text/plain" && it.disposition != "attachment" }
        val text = body?.let { Mime.decodeBody(s, it, FormatInspector.MAX_TEXT_CHARS) }
        return InspectionResult("eml", subject?.let { TC.cap(it, 200) } ?: "E-mail message",
            listOfNotNull(dec("from")?.let { "From $it" }, "${parts.size} part${if (parts.size == 1) "" else "s"}").joinToString(", "),
            facts, warnings, text)
    }
}

/** Unix mbox mailbox: message count and the first few subjects. */
object MboxInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("mbox")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source) }

    private fun run(source: ByteSource): InspectionResult? {
        val data = TC.readUpTo(source, FormatInspector.MAX_READ_BYTES)
        val s = String(data, Charsets.ISO_8859_1)
        if (!s.startsWith("From ")) return null
        val starts = ArrayList<Int>()
        var pos = 0
        var count = 0
        while (pos < s.length && count < 200_000) {
            val isFrom = s.startsWith("From ", pos) &&
                (pos == 0 || (s[pos - 1] == '\n' && (pos == 1 || s[pos - 2] == '\n' || (s[pos - 2] == '\r' && pos >= 3 && s[pos - 3] == '\n'))))
            if (isFrom) { count++; if (starts.size < 11) starts.add(pos) }
            val nl = s.indexOf('\n', pos)
            if (nl < 0) break
            pos = nl + 1
        }
        val truncated = TC.truncated(source, data)
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        facts.add(Fact("Messages", if (truncated) "at least $count" else count.toString(), "Mailbox"))
        if (truncated) warnings.add("File larger than the read limit; counted only the first ${Bytes.humanSize(data.size.toLong())}")
        for ((i, st) in starts.take(10).withIndex()) {
            val lineEnd = s.indexOf('\n', st).let { if (it < 0) s.length else it }
            val limit = if (i + 1 < starts.size) starts[i + 1] else s.length
            val (h, _) = Mime.parseHeaders(s, minOf(lineEnd + 1, s.length), limit)
            val subj = h.get("subject")?.let { Mime.decodeWords(it) } ?: "(no subject)"
            val from = h.get("from")?.let { Mime.decodeWords(it) }
            facts.add(Fact("Message ${i + 1}", TC.cap(subj, 160) + (if (from != null) " - " + TC.cap(from, 120) else ""), "Subjects"))
        }
        return InspectionResult("mbox", "mbox mailbox", "$count message${if (count == 1) "" else "s"}", facts, warnings)
    }
}
