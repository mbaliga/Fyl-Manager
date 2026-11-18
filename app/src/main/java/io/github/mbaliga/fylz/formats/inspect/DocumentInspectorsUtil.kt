package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Shared, total helpers for the document inspectors (internal to this package). */
internal object DocumentInspectorsUtil {

    fun readSafe(src: ByteSource, offset: Long, length: Int): ByteArray {
        return try {
            if (offset < 0 || length <= 0) return ByteArray(0)
            val size = src.size
            if (offset >= size) return ByteArray(0)
            val n = minOf(length.toLong(), size - offset).toInt()
            src.read(offset, n)
        } catch (t: Throwable) {
            ByteArray(0)
        }
    }

    fun latin1(b: ByteArray): String = String(b, Charsets.ISO_8859_1)

    fun clip(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max) + "..."

    fun extensionOf(fileName: String?): String? {
        val n = fileName ?: return null
        val dot = n.lastIndexOf('.')
        if (dot < 0 || dot == n.length - 1) return null
        return n.substring(dot + 1).lowercase()
    }

    fun baseName(fileName: String?): String? {
        val n = fileName ?: return null
        val s = n.substringAfterLast('/').substringAfterLast('\\')
        return s.ifBlank { null }
    }

    fun charsetFor(codePage: Int): java.nio.charset.Charset {
        when (codePage) {
            65001 -> return Charsets.UTF_8
            1200 -> return Charsets.UTF_16LE
            1201 -> return Charsets.UTF_16BE
            20127 -> return Charsets.US_ASCII
            28591 -> return Charsets.ISO_8859_1
        }
        for (name in listOf("windows-$codePage", "cp$codePage", "x-mac-$codePage")) {
            try { return java.nio.charset.Charset.forName(name) } catch (t: Throwable) { /* try next */ }
        }
        return Charsets.ISO_8859_1
    }

    /** 100 ns ticks since 1601-01-01 UTC -> ISO-8601, or null when zero or out of range. */
    fun filetimeIso(ticks: Long): String? {
        if (ticks <= 0L) return null
        val secs = ticks / 10_000_000L - 11_644_473_600L
        if (secs < 0L || secs > 253_402_300_799L) return null
        return try { java.time.Instant.ofEpochSecond(secs).toString() } catch (t: Throwable) { null }
    }

    // ---- minimal, entity-safe XML scanning (no DOM, no DTD processing) ----

    fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                var semi = -1
                val lim = minOf(s.length - 1, i + 10)
                var k = i + 1
                while (k <= lim) { if (s[k] == ';') { semi = k; break }; k++ }
                if (semi >= i + 2 && semi <= i + 10) {
                    val ent = s.substring(i + 1, semi)
                    val rep: String? = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { cp(it) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { cp(it) }
                        else -> null
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun cp(i: Int): String? =
        if (i in 1..0x10FFFF && i !in 0xD800..0xDFFF) String(Character.toChars(i)) else null

    fun localName(qname: String): String {
        val c = qname.lastIndexOf(':')
        return if (c >= 0) qname.substring(c + 1) else qname
    }

    class Elem(val start: Int, val gt: Int, val qname: String, val selfClosing: Boolean) {
        fun open(xml: String): String = xml.substring(start, gt + 1)
    }

    /** Next start tag whose local name is [local], at or after [from]. */
    fun nextElem(xml: String, local: String, from: Int): Elem? {
        var i = maxOf(0, from)
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0 || lt + 1 >= xml.length) return null
            val c = xml[lt + 1]
            if (c == '/' || c == '?' || c == '!') { i = lt + 2; continue }
            var j = lt + 1
            while (j < xml.length && !xml[j].isWhitespace() && xml[j] != '>' && xml[j] != '/') j++
            val gt = xml.indexOf('>', j)
            if (gt < 0) return null
            val q = xml.substring(lt + 1, j)
            if (localName(q) == local) return Elem(lt, gt, q, xml[gt - 1] == '/')
            i = gt + 1
        }
        return null
    }

    /** Text content of the first element with this local name, entities decoded; null when absent/blank. */
    fun tag(xml: String, local: String, from: Int = 0): String? {
        val e = nextElem(xml, local, from) ?: return null
        if (e.selfClosing) return null
        val close = "</" + e.qname + ">"
        val end = xml.indexOf(close, e.gt + 1)
        if (end < 0) return null
        val raw = xml.substring(e.gt + 1, end).replace("<![CDATA[", "").replace("]]>", "")
        val t = decodeEntities(raw).trim()
        return t.ifEmpty { null }
    }

    fun tagAll(xml: String, local: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (out.size < max) {
            val e = nextElem(xml, local, from) ?: break
            from = e.gt + 1
            if (e.selfClosing) continue
            val end = xml.indexOf("</" + e.qname + ">", e.gt + 1)
            if (end < 0) break
            val t = decodeEntities(xml.substring(e.gt + 1, end).replace("<![CDATA[", "").replace("]]>", "")).trim()
            if (t.isNotEmpty()) out.add(t)
            from = end
        }
        return out
    }

    fun attr(openTag: String, local: String): String? {
        val re = Regex("(?:^|[\\s:])" + Regex.escape(local) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
        val m = re.find(openTag) ?: return null
        val v = if (m.groups[1] != null) m.groupValues[1] else m.groupValues[2]
        return decodeEntities(v)
    }

    fun count(xml: String, local: String, max: Int): Int {
        var n = 0
        var from = 0
        while (n < max) {
            val e = nextElem(xml, local, from) ?: break
            n++
            from = e.gt + 1
        }
        return n
    }
}

/** Accumulates facts/warnings with trimming, clipping and caps. */
internal class DocumentInspectorsFacts {
    val facts = ArrayList<Fact>()
    val warnings = ArrayList<String>()

    fun add(group: String, label: String, value: String?) {
        if (value == null || facts.size >= 400) return
        val v = value.trim()
        if (v.isNotEmpty()) facts.add(Fact(label, DocumentInspectorsUtil.clip(v, 1000), group))
    }

    fun add(group: String, label: String, n: Long?) {
        if (n != null) add(group, label, n.toString())
    }

    fun warn(w: String) { if (warnings.size < 50) warnings.add(w) }
}
