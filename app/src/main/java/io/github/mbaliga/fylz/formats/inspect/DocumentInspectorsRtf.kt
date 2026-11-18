package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

private val U = DocumentInspectorsUtil

/** RTF inspector: version, character set, info group (title/author/dates/statistics), plain-text excerpt. */
object DocumentInspectorsRtf : FormatInspector {
    override val formatIds: Set<String> = setOf("rtf")

    private const val SCAN = 256 * 1024
    private const val MAX_DEPTH = 2000

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { impl(source, fileName) } catch (t: Throwable) { null }

    private val DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "headerf",
        "footerl", "footerr", "footerf", "footnote", "object", "themedata", "colorschememapping", "latentstyles",
        "datastore", "listtable", "listoverridetable", "rsidtbl", "xmlnstbl", "fldinst", "private", "generator",
        "filetbl", "revtbl", "pgptbl", "template", "bkmkstart", "bkmkend", "shpinst", "nonshppict", "blipuid",
    )

    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** Decode RTF [s] to plain text; destination groups and `\*` groups are dropped. */
    internal fun extractText(s: String, charset: java.nio.charset.Charset, maxChars: Int): String {
        val sb = StringBuilder()
        val stack = ArrayList<Int>()
        var skip = false
        var uc = 1
        var first = false
        var i = 0
        val n = s.length
        while (i < n && sb.length < maxChars) {
            val c = s[i]
            when (c) {
                '{' -> {
                    if (stack.size >= MAX_DEPTH) break
                    stack.add((if (skip) 1 else 0) or (uc shl 1))
                    first = true
                    i++
                }
                '}' -> {
                    if (stack.isNotEmpty()) {
                        val v = stack.removeAt(stack.size - 1)
                        skip = (v and 1) != 0
                        uc = v shr 1
                    }
                    first = false
                    i++
                }
                '\r', '\n' -> i++
                '\\' -> {
                    if (i + 1 >= n) { i++; continue }
                    val d = s[i + 1]
                    when {
                        d == '\\' || d == '{' || d == '}' -> { if (!skip) sb.append(d); i += 2; first = false }
                        d == '\'' -> {
                            val h1 = if (i + 2 < n) Character.digit(s[i + 2], 16) else -1
                            val h2 = if (i + 3 < n) Character.digit(s[i + 3], 16) else -1
                            if (h1 >= 0 && h2 >= 0) {
                                if (!skip) sb.append(String(byteArrayOf(((h1 shl 4) or h2).toByte()), charset))
                                i += 4
                            } else i += 2
                            first = false
                        }
                        d == '*' -> { skip = true; i += 2 }
                        d == '~' -> { if (!skip) sb.append(' '); i += 2; first = false }
                        d == '-' -> { i += 2 }
                        d == '_' -> { if (!skip) sb.append('‑'); i += 2; first = false }
                        d == '\r' || d == '\n' -> { if (!skip) sb.append('\n'); i += 2; first = false }
                        isLetter(d) -> {
                            var j = i + 1
                            while (j < n && isLetter(s[j]) && j - i < 40) j++
                            val word = s.substring(i + 1, j)
                            var param: Int? = null
                            var k = j
                            var neg = false
                            if (k < n && s[k] == '-') { neg = true; k++ }
                            val ds = k
                            var v = 0L
                            while (k < n && s[k].isDigit() && k - ds < 10) { v = v * 10 + (s[k] - '0'); k++ }
                            if (k > ds) param = (if (neg) -v else v).coerceIn(-2_000_000_000L, 2_000_000_000L).toInt()
                            else k = j
                            if (k < n && s[k] == ' ') k++
                            if (first && word in DESTINATIONS) skip = true
                            first = false
                            i = k
                            when (word) {
                                "par", "line", "sect", "page" -> if (!skip) sb.append('\n')
                                "tab" -> if (!skip) sb.append('\t')
                                "emdash" -> if (!skip) sb.append('—')
                                "endash" -> if (!skip) sb.append('–')
                                "bullet" -> if (!skip) sb.append('•')
                                "lquote" -> if (!skip) sb.append('‘')
                                "rquote" -> if (!skip) sb.append('’')
                                "ldblquote" -> if (!skip) sb.append('“')
                                "rdblquote" -> if (!skip) sb.append('”')
                                "uc" -> if (param != null) uc = param.coerceIn(0, 16)
                                "bin" -> if (param != null && param > 0) i = minOf(n.toLong(), i.toLong() + param).toInt()
                                "u" -> if (param != null) {
                                    val cu = (if (param < 0) param + 65536 else param) and 0xFFFF
                                    if (!skip) sb.append(cu.toChar())
                                    var skipN = uc
                                    while (skipN > 0 && i < n) {
                                        if (s[i] == '\\' && i + 1 < n && s[i + 1] == '\'') i += 4 else i++
                                        skipN--
                                    }
                                }
                            }
                        }
                        else -> { i += 2; first = false }
                    }
                }
                else -> {
                    if (!skip) sb.append(c)
                    first = false
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** Index just past the group starting at [open] ('{'), honouring escapes; capped. */
    private fun groupEnd(s: String, open: Int, cap: Int): Int {
        var depth = 0
        var i = open
        val end = minOf(s.length, open + cap)
        while (i < end) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == '{') depth++
            else if (c == '}') { depth--; if (depth == 0) return i + 1 }
            i++
        }
        return end
    }

    private fun subGroup(info: String, name: String): String? {
        var from = 0
        var guard = 0
        while (guard++ < 1000) {
            val p = info.indexOf("{\\$name", from)
            if (p < 0) return null
            val e = p + 2 + name.length
            if (e >= info.length || !isLetter(info[e])) {
                val end = groupEnd(info, p, info.length)
                return info.substring(p, end)
            }
            from = p + 1
        }
        return null
    }

    private fun param(group: String?, word: String): Int? {
        if (group == null) return null
        val m = Regex("\\\\" + word + "(-?\\d{1,9})").find(group) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun date(group: String?): String? {
        if (group == null) return null
        val y = param(group, "yr") ?: return null
        val mo = param(group, "mo")
        val d = param(group, "dy")
        val h = param(group, "hr")
        val mi = param(group, "min")
        val se = param(group, "sec")
        val sb = StringBuilder("%04d".format(y))
        if (mo != null) sb.append("-%02d".format(mo))
        if (mo != null && d != null) sb.append("-%02d".format(d))
        if (h != null && mi != null) {
            sb.append("T%02d:%02d".format(h, mi))
            if (se != null) sb.append(":%02d".format(se))
        }
        return sb.toString()
    }

    private fun impl(source: ByteSource, fileName: String?): InspectionResult? {
        val raw = U.readSafe(source, 0, SCAN)
        val s = U.latin1(raw)
        var st = 0
        while (st < s.length && st < 16 && (s[st].isWhitespace() || s[st] == '\u0000')) st++
        if (!s.startsWith("{\\rtf", st)) return null
        val out = DocumentInspectorsFacts()
        val gHdr = "Header"; val gDoc = "Document"; val gStat = "Statistics"

        var p = st + 5
        val vs = p
        while (p < s.length && s[p].isDigit() && p - vs < 4) p++
        val version = s.substring(vs, p).ifEmpty { null }
        out.add(gHdr, "RTF version", version)

        val header = s.substring(0, minOf(s.length, 4096))
        fun hasWord(w: String): Boolean {
            var i = header.indexOf("\\$w")
            while (i >= 0) {
                val e = i + 1 + w.length
                if (e >= header.length || !isLetter(header[e])) return true
                i = header.indexOf("\\$w", i + 1)
            }
            return false
        }
        val charsetName = when {
            hasWord("ansi") -> "ANSI"
            hasWord("mac") -> "Apple Macintosh"
            hasWord("pca") -> "IBM PC code page 850"
            hasWord("pc") -> "IBM PC code page 437"
            else -> null
        }
        out.add(gHdr, "Character set", charsetName)
        val cpg = Regex("\\\\ansicpg(\\d{1,6})").find(header)?.groupValues?.get(1)?.toIntOrNull()
        out.add(gHdr, "ANSI code page", cpg?.toString())
        val deff = param(header, "deff")
        out.add(gHdr, "Default font number", deff?.toLong())
        val charset = if (cpg != null) U.charsetFor(cpg) else Charsets.ISO_8859_1

        val gp = s.indexOf("\\generator")
        if (gp >= 0) {
            val ge = s.indexOf('}', gp)
            if (ge > gp && ge - gp < 200) out.add(gHdr, "Generator", s.substring(gp + 10, ge).trim().trimEnd(';').trim())
        }

        var title: String? = null
        val ip = s.indexOf("{\\info")
        var infoText: String? = null
        if (ip >= 0 && (ip + 6 >= s.length || !isLetter(s[ip + 6]))) {
            val ie = groupEnd(s, ip, 64 * 1024)
            val info = s.substring(ip, ie)
            fun field(name: String): String? =
                subGroup(info.substring(1), name)?.let { extractText(it, charset, 2000).trim().ifEmpty { null } }
            // subGroup searches "{\name" so give it the info text; the outer "{\info" never matches inner names.
            title = field("title")
            out.add(gDoc, "Title", title)
            out.add(gDoc, "Subject", field("subject"))
            out.add(gDoc, "Author", field("author"))
            out.add(gDoc, "Manager", field("manager"))
            out.add(gDoc, "Company", field("company"))
            out.add(gDoc, "Operator (last saved by)", field("operator"))
            out.add(gDoc, "Category", field("category"))
            out.add(gDoc, "Keywords", field("keywords"))
            out.add(gDoc, "Comments", field("doccomm"))
            out.add(gDoc, "Created", date(subGroup(info, "creatim")))
            out.add(gDoc, "Revised", date(subGroup(info, "revtim")))
            out.add(gDoc, "Printed", date(subGroup(info, "printim")))
            out.add(gDoc, "Backed up", date(subGroup(info, "buptim")))
            out.add(gStat, "Pages", param(info, "nofpages")?.toLong())
            out.add(gStat, "Words", param(info, "nofwords")?.toLong())
            out.add(gStat, "Characters", param(info, "nofchars")?.toLong())
            out.add(gStat, "Characters (with spaces)", param(info, "nofcharsws")?.toLong())
            out.add(gDoc, "Document version", param(info, "version")?.toLong())
            out.add(gDoc, "Revision number", param(info, "vern")?.toLong())
            out.add(gDoc, "Total editing time (minutes)", param(info, "edmins")?.toLong())
            infoText = info
        } else if (ip < 0 && s.length >= SCAN) {
            out.warn("No \\info group in the first ${Bytes.humanSize(SCAN.toLong())}; metadata (if any) was not read.")
        }

        if (source.size > SCAN) out.warn("Only the first ${Bytes.humanSize(SCAN.toLong())} were scanned; the text is an excerpt.")
        if (infoText == null && s.length < SCAN && !s.trimEnd().endsWith("}")) out.warn("The file does not end with a closing brace (truncated?).")

        val text = extractText(s, charset, FormatInspector.MAX_TEXT_CHARS).trim().ifEmpty { null }
        val summary = "Rich Text Format" + (version?.let { " $it" } ?: "") + (charsetName?.let { " · $it" } ?: "")
        return InspectionResult("rtf", title ?: U.baseName(fileName) ?: "RTF document", summary, out.facts, out.warnings, text)
    }
}
