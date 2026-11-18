package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

private val U = DocumentInspectorsUtil

/**
 * PDF inspector: header version, linearization, encryption, page count, Info dictionary.
 * Works on the plain-text structure only; objects inside compressed object streams are not read
 * and the inspector says so instead of guessing.
 */
object DocumentInspectorsPdf : FormatInspector {
    override val formatIds: Set<String> = setOf("pdf")

    private const val SCAN_LIMIT = 8 * 1024 * 1024
    private const val HALF = 4 * 1024 * 1024
    private const val MAX_OBJ = 64 * 1024
    private const val MAX_STR = 8192

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { impl(source, fileName) } catch (t: Throwable) { null }

    // ---- low-level scanning helpers ----

    private fun isWs(c: Char) = c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\u000C' || c == '\u0000'
    private fun isDelim(c: Char) = c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' ||
        c == '{' || c == '}' || c == '/' || c == '%'
    private fun boundary(s: String, end: Int) = end >= s.length || isWs(s[end]) || isDelim(s[end])

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && isWs(s[i])) i++
        return i
    }

    /** Index just after the first occurrence of [key] (a name like "/Count") in [from, to), or -1. */
    private fun findKey(s: String, key: String, from: Int, to: Int): Int {
        var i = s.indexOf(key, maxOf(0, from))
        var guard = 0
        while (i >= 0 && i < to && guard++ < 1_000_000) {
            val e = i + key.length
            if (boundary(s, e)) return e
            i = s.indexOf(key, i + 1)
        }
        return -1
    }

    private fun findLastKey(s: String, key: String): Int {
        var i = s.lastIndexOf(key)
        var guard = 0
        while (i >= 0 && guard++ < 1_000_000) {
            val e = i + key.length
            if (boundary(s, e)) return e
            if (i == 0) return -1
            i = s.lastIndexOf(key, i - 1)
        }
        return -1
    }

    private fun numAt(s: String, pos: Int): Long? {
        var i = skipWs(s, pos)
        val st = i
        var v = 0L
        while (i < s.length && s[i].isDigit() && i - st < 18) { v = v * 10 + (s[i] - '0'); i++ }
        return if (i == st) null else v
    }

    private fun keyNum(s: String, key: String, from: Int, to: Int): Long? {
        val p = findKey(s, key, from, to)
        return if (p < 0) null else numAt(s, p)
    }

    /** Amortised "first boundary-terminated occurrence of key at or after a non-decreasing position". */
    private class KeyCache(val s: String, val key: String) {
        private var pos = -2
        private var none = false
        fun next(from: Int): Int {
            if (none) return -1
            if (pos >= from) return pos
            var i = s.indexOf(key, maxOf(0, from))
            var guard = 0
            while (i >= 0 && guard++ < 1_000_000) {
                if (boundary(s, i + key.length)) { pos = i; return i }
                i = s.indexOf(key, i + 1)
            }
            none = true
            return -1
        }
    }

    private class Ref(val n: Long, val g: Long)

    private fun parseRef(s: String, pos: Int): Ref? {
        var i = skipWs(s, pos)
        var st = i
        var n = 0L
        while (i < s.length && s[i].isDigit() && i - st < 12) { n = n * 10 + (s[i] - '0'); i++ }
        if (i == st) return null
        val j = skipWs(s, i)
        if (j == i) return null
        i = j
        st = i
        var g = 0L
        while (i < s.length && s[i].isDigit() && i - st < 8) { g = g * 10 + (s[i] - '0'); i++ }
        if (i == st) return null
        val k = skipWs(s, i)
        if (k == i || k >= s.length || s[k] != 'R' || !boundary(s, k + 1)) return null
        return Ref(n, g)
    }

    /** Body range of the last "n g obj ... endobj" in [s]. */
    private fun findObject(s: String, r: Ref): IntArray? {
        val needle = "${r.n} ${r.g} obj"
        var i = s.lastIndexOf(needle)
        var guard = 0
        while (i >= 0 && guard++ < 64) {
            val before = if (i > 0) s[i - 1] else '\n'
            if (!before.isDigit()) {
                val start = i + needle.length
                var end = s.indexOf("endobj", start)
                if (end < 0 || end - start > MAX_OBJ) end = minOf(s.length, start + MAX_OBJ)
                return intArrayOf(start, end)
            }
            i = if (i == 0) -1 else s.lastIndexOf(needle, i - 1)
        }
        return null
    }

    private fun readLiteral(s: String, pos: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = pos + 1
        var depth = 1
        while (i < s.length && out.size() < MAX_STR) {
            val c = s[i]
            if (c == '\\') {
                if (i + 1 >= s.length) break
                val n = s[i + 1]
                when {
                    n == 'n' -> { out.write(10); i += 2 }
                    n == 'r' -> { out.write(13); i += 2 }
                    n == 't' -> { out.write(9); i += 2 }
                    n == 'b' -> { out.write(8); i += 2 }
                    n == 'f' -> { out.write(12); i += 2 }
                    n in '0'..'7' -> {
                        var v = 0; var k = 0
                        while (k < 3 && i + 1 + k < s.length && s[i + 1 + k] in '0'..'7') { v = v * 8 + (s[i + 1 + k] - '0'); k++ }
                        out.write(v and 0xFF); i += 1 + k
                    }
                    n == '\r' -> { i += 2; if (i < s.length && s[i] == '\n') i++ }
                    n == '\n' -> i += 2
                    else -> { out.write(n.code and 0xFF); i += 2 }
                }
            } else if (c == '(') { depth++; out.write(c.code); i++ }
            else if (c == ')') { depth--; if (depth == 0) break; out.write(c.code); i++ }
            else { out.write(c.code and 0xFF); i++ }
        }
        return out.toByteArray()
    }

    private fun readHex(s: String, pos: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = pos + 1
        var hi = -1
        while (i < s.length && out.size() < MAX_STR) {
            val c = s[i]
            if (c == '>') break
            val d = Character.digit(c, 16)
            if (d >= 0) {
                if (hi < 0) hi = d else { out.write((hi shl 4) or d); hi = -1 }
            }
            i++
        }
        if (hi >= 0) out.write(hi shl 4)
        return out.toByteArray()
    }

    private val PDF_DOC_HIGH = charArrayOf(
        '•', '†', '‡', '…', '—', '–', 'ƒ', '⁄',
        '‹', '›', '−', '‰', '„', '“', '”', '‘',
        '’', '‚', '™', 'ﬁ', 'ﬂ', 'Ł', 'Œ', 'Š',
        'Ÿ', 'Ž', 'ı', 'ł', 'œ', 'š', 'ž', '�',
        '€',
    ) // 0x80..0xA0
    private val PDF_DOC_ACCENTS = charArrayOf(
        '˘', 'ˇ', 'ˆ', '˙', '˝', '˛', '˚', '˜',
    ) // 0x18..0x1F

    /** Decode a PDF text string: UTF-16BE/LE (BOM), UTF-8 (BOM) or PDFDocEncoding. */
    internal fun decodeText(b: ByteArray): String {
        if (b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xFF) == 0xFF)
            return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        if (b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xFE)
            return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 3 && (b[0].toInt() and 0xFF) == 0xEF && (b[1].toInt() and 0xFF) == 0xBB && (b[2].toInt() and 0xFF) == 0xBF)
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        val sb = StringBuilder(b.size)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append(
                when {
                    v in 0x18..0x1F -> PDF_DOC_ACCENTS[v - 0x18]
                    v in 0x80..0xA0 -> PDF_DOC_HIGH[v - 0x80]
                    v == 0x7F -> '�'
                    v < 0x20 -> if (v == 9 || v == 10 || v == 13) v.toChar() else '�'
                    else -> v.toChar() // 0x20..0x7E ASCII, 0xA1..0xFF same as Latin-1
                }
            )
        }
        return sb.toString()
    }

    private fun stringAt(s: String, pos: Int, depth: Int = 0): String? {
        val q = skipWs(s, pos)
        if (q >= s.length) return null
        val c = s[q]
        if (c == '(') return decodeText(readLiteral(s, q))
        if (c == '<' && q + 1 < s.length && s[q + 1] != '<') return decodeText(readHex(s, q))
        if (depth == 0) {
            val ref = parseRef(s, q) ?: return null
            val o = findObject(s, ref) ?: return null
            return stringAt(s, o[0], 1)
        }
        return null
    }

    private fun stringKey(s: String, key: String, from: Int, to: Int): String? {
        val p = findKey(s, key, from, to)
        if (p < 0) return null
        return stringAt(s, p)?.trim()?.ifEmpty { null }
    }

    /** "D:20210304050607+01'00'" -> "2021-03-04T05:06:07+01:00"; unparseable input is returned as is. */
    internal fun formatDate(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("D:")) t = t.substring(2)
        var i = 0
        fun digits(n: Int): String? {
            if (i + n > t.length) return null
            for (k in 0 until n) if (!t[i + k].isDigit()) return null
            val r = t.substring(i, i + n); i += n; return r
        }
        val y = digits(4) ?: return raw
        val mo = digits(2); val d = if (mo != null) digits(2) else null
        val h = if (d != null) digits(2) else null
        val mi = if (h != null) digits(2) else null
        val se = if (mi != null) digits(2) else null
        val sb = StringBuilder(y)
        if (mo != null) sb.append('-').append(mo)
        if (d != null) sb.append('-').append(d)
        if (h != null) {
            sb.append('T').append(h).append(':').append(mi ?: "00").append(':').append(se ?: "00")
            if (i < t.length) {
                val z = t[i]
                if (z == 'Z') sb.append('Z')
                else if (z == '+' || z == '-') {
                    i++
                    val zh = digits(2)
                    if (zh != null) {
                        if (i < t.length && t[i] == '\'') i++
                        val zm = digits(2) ?: "00"
                        sb.append(z).append(zh).append(':').append(zm)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun nameAt(s: String, pos: Int): String? {
        val q = skipWs(s, pos)
        if (q >= s.length || s[q] != '/') return null
        var e = q + 1
        while (e < s.length && !isWs(s[e]) && !isDelim(s[e]) && e - q < 64) e++
        return s.substring(q + 1, e)
    }

    // ---- main ----

    private fun impl(source: ByteSource, fileName: String?): InspectionResult? {
        val size = source.size
        if (size < 8) return null
        val headStr = U.latin1(U.readSafe(source, 0, 1024))
        val hp = headStr.indexOf("%PDF-")
        if (hp < 0) return null
        val vm = Regex("%PDF-(\\d{1,3})\\.(\\d{1,3})").find(headStr, hp)
        val version = vm?.let { it.groupValues[1] + "." + it.groupValues[2] }

        val complete = size <= SCAN_LIMIT
        val s: String = if (complete) {
            U.latin1(U.readSafe(source, 0, size.toInt()))
        } else {
            U.latin1(U.readSafe(source, 0, HALF)) + "\n" + U.latin1(U.readSafe(source, size - HALF, HALF))
        }

        val out = DocumentInspectorsFacts()
        val g1 = "Document"
        val gp = "Producer"
        val gd = "Dates"
        val gs = "Structure"
        val gf = "Features"

        // Linearization hint dictionary: must be the first object, near the start.
        val headEnd = minOf(s.length, 4096)
        var linearized = false
        var linN: Long? = null
        var linL: Long? = null
        val lk = findKey(s, "/Linearized", 0, headEnd)
        if (lk >= 0 && s.lastIndexOf("obj", lk) in 0 until lk) {
            linearized = true
            linN = keyNum(s, "/N", lk, minOf(s.length, lk + 512))
            linL = keyNum(s, "/L", lk, minOf(s.length, lk + 512))
        }

        // Page count: /Count of the root Pages node (no /Parent), else linearization /N, else leaf count.
        var pages: Long? = null
        var pagesSource: String? = null
        var objStm = false
        run {
            var i = s.indexOf("/Type")
            var guard = 0
            var rootCount: Long? = null
            var leaves = 0L
            val parentC = KeyCache(s, "/Parent")
            val countC = KeyCache(s, "/Count")
            var objScan = 0
            var lastObj = -1
            var endPos = -2
            var endNone = false
            var lastSt = 0
            while (i >= 0 && guard++ < 4_000_000) {
                val e = i + 5
                if (boundary(s, e)) {
                    val j = skipWs(s, e)
                    if (j < s.length && s[j] == '/') {
                        if (s.startsWith("/Pages", j) && boundary(s, j + 6)) {
                            while (true) {
                                val o = s.indexOf("obj", objScan)
                                if (o in 0..i) { lastObj = o; objScan = o + 1 } else break
                            }
                            var st = maxOf(0, if (lastObj < 0 || i - lastObj > MAX_OBJ) i - MAX_OBJ else lastObj)
                            if (st < lastSt) st = lastSt
                            lastSt = st
                            if (!endNone && endPos < j) {
                                endPos = s.indexOf("endobj", j)
                                if (endPos < 0) endNone = true
                            }
                            var en = if (endNone) -1 else endPos
                            if (en < 0 || en - j > MAX_OBJ) en = minOf(s.length, j + MAX_OBJ)
                            val pp = parentC.next(st)
                            if (pp < 0 || pp >= en) {
                                val cp = countC.next(st)
                                if (cp >= 0 && cp < en) {
                                    val c = numAt(s, cp + 6)
                                    if (c != null) rootCount = c
                                }
                            }
                        } else if (s.startsWith("/Page", j) && boundary(s, j + 5)) {
                            leaves++
                        } else if (s.startsWith("/ObjStm", j) && boundary(s, j + 7)) {
                            objStm = true
                        }
                    }
                }
                i = s.indexOf("/Type", i + 5)
            }
            if (rootCount != null) { pages = rootCount; pagesSource = "page tree /Count" }
            else if (linN != null) { pages = linN; pagesSource = "linearization hint /N" }
            else if (complete && leaves > 0) {
                pages = leaves
                pagesSource = if (objStm) "counted /Type /Page objects (at least; some objects are compressed)"
                else "counted /Type /Page objects"
            }
        }

        // Encryption
        var encrypted = false
        var encFilter: String? = null
        var encV: Long? = null
        var encR: Long? = null
        val ek = findLastKey(s, "/Encrypt")
        if (ek >= 0) {
            encrypted = true
            val q = skipWs(s, ek)
            var a = -1; var b = -1
            if (s.startsWith("<<", q)) { a = q; b = minOf(s.length, q + 2048) }
            else {
                val ref = parseRef(s, q)
                val o = if (ref != null) findObject(s, ref) else null
                if (o != null) { a = o[0]; b = minOf(o[1], o[0] + 4096) }
            }
            if (a >= 0) {
                val fk = findKey(s, "/Filter", a, b)
                encFilter = if (fk >= 0) nameAt(s, fk) else null
                encV = keyNum(s, "/V", a, b)
                encR = keyNum(s, "/R", a, b)
            }
        }

        // Info dictionary
        var title: String? = null
        var author: String? = null
        var subject: String? = null
        var keywords: String? = null
        var creator: String? = null
        var producer: String? = null
        var created: String? = null
        var modified: String? = null
        var infoFound = false
        var infoRefSeen = false
        run {
            var p = findLastKey(s, "/Info")
            var guard = 0
            while (p >= 0 && guard++ < 8) {
                val ref = parseRef(s, p)
                if (ref != null) {
                    infoRefSeen = true
                    val o = findObject(s, ref)
                    if (o != null && skipWs(s, o[0]).let { it + 1 < s.length && s[it] == '<' && s[it + 1] == '<' }) {
                        infoFound = true
                        val a = o[0]; val b = o[1]
                        title = stringKey(s, "/Title", a, b)
                        author = stringKey(s, "/Author", a, b)
                        subject = stringKey(s, "/Subject", a, b)
                        keywords = stringKey(s, "/Keywords", a, b)
                        creator = stringKey(s, "/Creator", a, b)
                        producer = stringKey(s, "/Producer", a, b)
                        created = stringKey(s, "/CreationDate", a, b)?.let { formatDate(it) }
                        modified = stringKey(s, "/ModDate", a, b)?.let { formatDate(it) }
                        break
                    }
                }
                if (p < 6) break
                val prev = s.lastIndexOf("/Info", p - 6)
                p = if (prev < 0) -1 else {
                    val e = prev + 5
                    if (boundary(s, e)) e else -1
                }
            }
        }

        out.add(g1, "Title", title)
        out.add(g1, "Author", author)
        out.add(g1, "Subject", subject)
        out.add(g1, "Keywords", keywords)
        out.add(gp, "Creator", creator)
        out.add(gp, "Producer", producer)
        out.add(gd, "Created", created)
        out.add(gd, "Modified", modified)
        out.add(gs, "PDF version", version)
        out.add(gs, "Linearized", if (linearized) "yes (fast web view)" else "no")
        if (linearized && linL != null) {
            out.add(gs, "Linearization file length", linL)
            if (linL != size) out.warn("Linearization length hint ($linL) differs from the file size ($size): the file was modified after linearization.")
        }
        out.add(gs, "Pages", pages)
        out.add(gs, "Page count source", pagesSource)
        out.add(gs, "Encrypted", if (encrypted) "yes" else if (complete) "no" else "no /Encrypt entry in scanned regions")
        if (encrypted) {
            out.add(gs, "Encryption handler", encFilter)
            out.add(gs, "Encryption algorithm version (V)", encV)
            out.add(gs, "Encryption revision (R)", encR)
            val v = when (encV) {
                1L -> "RC4, 40-bit key"
                2L -> "RC4, variable key length"
                4L -> "crypt filters (RC4 or AES-128)"
                5L -> "AES-256"
                else -> null
            }
            out.add(gs, "Encryption method", v)
        }
        if (!complete) out.warn("File is larger than ${Bytes.humanSize(SCAN_LIMIT.toLong())}: only the first and last ${Bytes.humanSize(HALF.toLong())} were scanned.")

        val feats = ArrayList<String>()
        if (findKey(s, "/AcroForm", 0, s.length) >= 0) feats.add("interactive form")
        if (findKey(s, "/JavaScript", 0, s.length) >= 0 || findKey(s, "/JS", 0, s.length) >= 0) feats.add("JavaScript")
        if (findKey(s, "/EmbeddedFile", 0, s.length) >= 0) feats.add("embedded files")
        if (feats.isNotEmpty()) out.add(gf, "Contains (in uncompressed objects)", feats.joinToString(", "))

        if (!infoFound) {
            if (infoRefSeen || objStm) out.warn("Info dictionary was not found in plain objects (it may be inside a compressed object stream); metadata not read.")
        }
        val tailStr = s.substring(maxOf(0, s.length - 2048))
        if (!tailStr.contains("%%EOF")) out.warn("No %%EOF marker near the end of the file (truncated or damaged?).")
        if (version == null) out.warn("PDF header version could not be parsed.")
        if (hp > 0) out.warn("The %PDF- header does not start at byte 0 (junk before it: $hp bytes).")

        val summary = buildString {
            append("PDF").append(if (version != null) " $version" else "")
            if (pages != null) append(" · ").append(pages).append(if (pages == 1L) " page" else " pages")
            if (encrypted) append(" · encrypted")
        }
        return InspectionResult(
            formatId = "pdf",
            title = title ?: U.baseName(fileName) ?: "PDF document",
            summary = summary,
            facts = out.facts,
            warnings = out.warnings,
        )
    }
}
