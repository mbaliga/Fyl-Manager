package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

private const val TEXT_WINDOW = 2 * 1024 * 1024

private fun looksBinary(b: ByteArray): Boolean {
    val n = minOf(b.size, 4096)
    if (n >= 2 && ((b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xFF) == 0xFF || (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xFE)) return false
    for (i in 0 until n) if (b[i].toInt() == 0) return true
    return false
}

private fun ext(fileName: String?): String = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""

/** CSV / TSV: delimiter detection, header guess, column and row counts over a bounded window. */
object GisScienceCsvInspector : FormatInspector {
    override val formatIds = setOf("csv", "tsv")
    private val DELIMS = charArrayOf(',', ';', '\t', '|')

    /** Parse up to [maxRecords] records with quote awareness; returns records and whether quoting was seen. */
    private fun parse(text: String, d: Char, maxRecords: Int): Pair<List<List<String>>, Boolean> {
        val recs = ArrayList<List<String>>()
        var cur = ArrayList<String>(); val sb = StringBuilder()
        var inQ = false; var quoted = false; var i = 0; var cellQuoted = false
        while (i < text.length && recs.size < maxRecords) {
            val c = text[i]
            if (inQ) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else inQ = false }
                else sb.append(c)
            } else when {
                c == '"' && sb.isEmpty() && !cellQuoted -> { inQ = true; quoted = true; cellQuoted = true }
                c == d -> { cur.add(sb.toString()); sb.setLength(0); cellQuoted = false }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    cur.add(sb.toString()); sb.setLength(0); cellQuoted = false
                    if (!(cur.size == 1 && cur[0].isEmpty())) recs.add(cur)
                    cur = ArrayList()
                }
                else -> sb.append(c)
            }
            i++
        }
        if (recs.size < maxRecords && (sb.isNotEmpty() || cur.isNotEmpty())) { cur.add(sb.toString()); recs.add(cur) }
        return recs to quoted
    }

    private fun isNum(s: String) = s.trim().toDoubleOrNull() != null

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val raw = rd.head(TEXT_WINDOW)
        if (raw.isEmpty() || looksBinary(raw)) return@guard null
        val text = GsKit.decodeText(raw)
        val f = GsFacts()
        val e = ext(fileName)
        val sample = text.take(256 * 1024)
        // choose delimiter by consistency over the first records
        var best = ','; var bestScore = -1.0; var bestCols = 1
        for (d in DELIMS) {
            val (recs, _) = parse(sample, d, 40)
            if (recs.isEmpty()) continue
            val counts = recs.groupingBy { it.size }.eachCount()
            val mode = counts.maxByOrNull { it.value }!!
            if (mode.key < 2) continue
            var score = mode.value.toDouble() / recs.size * 100 + minOf(mode.key, 20) * 0.01
            if ((d == '\t' && e == "tsv") || (d == ',' && e == "csv")) score += 0.5
            if (score > bestScore) { bestScore = score; best = d; bestCols = mode.key }
        }
        val single = bestScore < 0
        val id = if (e == "tsv" || (!single && best == '\t' && e != "csv")) "tsv" else "csv"
        val dname = when (best) { ',' -> "comma"; ';' -> "semicolon"; '\t' -> "tab"; else -> "pipe" }
        val (recs, quoted) = parse(text, best, 5_000_000)
        if (recs.isEmpty()) return@guard null
        val counts = recs.groupingBy { it.size }.eachCount()
        val cols = if (single) 1 else counts.maxByOrNull { it.value }!!.key
        f.add("Structure", "Delimiter", if (single) "none detected (single column or free text)" else dname)
        f.add("Structure", "Columns", cols)
        f.add("Structure", "Quoting", if (quoted) "double quotes used" else "no quoted fields seen")
        f.add("Structure", "Line endings", when { text.contains("\r\n") -> "CRLF"; text.contains('\n') -> "LF"; text.contains('\r') -> "CR"; else -> "none" })
        f.add("Structure", "Byte order mark", if (raw.size >= 3 && (raw[0].toInt() and 0xFF) == 0xEF && (raw[1].toInt() and 0xFF) == 0xBB) "UTF-8 BOM" else null)
        val partial = source.size > raw.size
        val total: String = if (!partial) recs.size.toString()
            else "about " + (recs.size.toLong() * source.size / maxOf(raw.size, 1)).toString() + " (estimated from first ${GsKit.mib(raw.size)})"
        f.add("Rows", if (partial) "Record count (estimate)" else "Record count (including header)", total)
        val first = recs[0]
        val header = first.all { it.isNotBlank() && !isNum(it) } && first.toSet().size == first.size &&
            (recs.size < 2 || recs.drop(1).take(20).any { r -> r.any { isNum(it) } } || first.size > 1)
        f.add("Header", "Header row (heuristic)", if (header) "likely" else "unlikely")
        if (header) f.add("Header", "Column names", first.take(40).joinToString(", ") { GsKit.clip(it, 60) })
        else f.add("Header", "First row", first.take(20).joinToString(" | ") { GsKit.clip(it, 40) })
        if (!single && counts.size > 1) f.warn("Rows have differing column counts: " + counts.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} cols x${it.value}" })
        if (partial) f.warn("Only the first ${GsKit.mib(raw.size)} was read")
        val rowsStr = if (partial) "~$total rows" else "${recs.size} rows"
        InspectionResult(id, if (id == "tsv") "Tab-separated values" else "Comma-separated values", "$cols columns, $rowsStr",
            f.facts, f.warnings, text = text.take(2000).ifEmpty { null })
    }
}

/** JSON / NDJSON: shape and key sample by a bounded, non-recursive scan (not a full validator). */
object GisScienceJsonInspector : FormatInspector {
    override val formatIds = setOf("json", "ndjson")

    private class Scan(val keys: ArrayList<String>, val kinds: ArrayList<String>, var depthMax: Int, var topCount: Long, var balanced: Boolean, var end: Int)

    private fun kindOf(c: Char) = when {
        c == '{' -> "object"; c == '[' -> "array"; c == '"' -> "string"; c == 't' || c == 'f' -> "boolean"; c == 'n' -> "null"
        c == '-' || c.isDigit() -> "number"; else -> "?"
    }

    /** Scans one JSON value starting at the first non-space of [s] from [from]. */
    private fun scan(s: String, from: Int, to: Int, wantKeys: Int): Scan {
        var i = from
        while (i < to && s[i].isWhitespace()) i++
        val keys = ArrayList<String>(); val kinds = ArrayList<String>()
        var depth = 0; var maxD = 0; var top = 0L; var any = false
        var inStr = false; var esc = false; var strStart = 0
        var pendingKey: String? = null
        val isObj = i < to && s[i] == '{'
        var topHasValue = false
        var lastTopTokenWasValueStart = false
        var expectKeyDepth1 = false
        while (i < to) {
            val c = s[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') {
                    inStr = false
                    if (depth == 1 && isObj && expectKeyDepth1) {
                        // a string at depth 1 that is followed by ':' is a key
                        var j = i + 1; while (j < to && s[j].isWhitespace()) j++
                        if (j < to && s[j] == ':') {
                            if (keys.size < wantKeys) {
                                keys.add(GsKit.clip(s.substring(strStart + 1, i), 80))
                                var k = j + 1; while (k < to && s[k].isWhitespace()) k++
                                kinds.add(if (k < to) kindOf(s[k]) else "?")
                            }
                            expectKeyDepth1 = false
                        }
                    }
                }
            } else when (c) {
                '"' -> { inStr = true; strStart = i; any = true; if (depth == 1 && !topHasValue) topHasValue = true }
                '{', '[' -> { depth++; if (depth > maxD) maxD = depth; any = true; if (depth == 1) expectKeyDepth1 = true }
                '}', ']' -> { depth--; if (depth == 0) { return Scan(keys, kinds, maxD, top + 0, true, i + 1) } }
                ',' -> { if (depth == 1) { top++; expectKeyDepth1 = true } }
                else -> {}
            }
            i++
        }
        return Scan(keys, kinds, maxD, top, false, to)
    }

    private fun topCountFor(s: String, from: Int, to: Int, isObj: Boolean): Long {
        // number of top-level elements/members = commas at depth 1 + 1 (if non-empty)
        var depth = 0; var inStr = false; var esc = false; var commas = 0L; var nonEmpty = false
        var i = from
        while (i < to) {
            val c = s[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false }
            else when (c) {
                '"' -> { inStr = true; if (depth == 1) nonEmpty = true }
                '{', '[' -> { depth++; if (depth == 2) nonEmpty = true }
                '}', ']' -> { depth--; if (depth == 0) return if (nonEmpty) commas + 1 else 0 }
                ',' -> if (depth == 1) commas++
                else -> if (depth == 1 && !c.isWhitespace()) nonEmpty = true
            }
            i++
        }
        return if (nonEmpty) commas + 1 else 0
    }

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val raw = rd.head(TEXT_WINDOW)
        if (raw.isEmpty() || looksBinary(raw)) return@guard null
        val text = GsKit.decodeText(raw)
        var start = 0
        while (start < text.length && text[start].isWhitespace()) start++
        if (start >= text.length) return@guard null
        val c0 = text[start]
        if (c0 != '{' && c0 != '[' && c0 != '"' && c0 != '-' && !c0.isDigit() && c0 != 't' && c0 != 'f' && c0 != 'n') return@guard null
        val e = ext(fileName)
        val f = GsFacts()
        val partial = source.size > raw.size
        // NDJSON test: several non-empty lines that each hold a complete top-level object/array
        val lines = text.split('\n')
        val nonEmpty = lines.filter { it.isNotBlank() }
        var ndjson = false
        if (nonEmpty.size >= 2 && (c0 == '{' || c0 == '[')) {
            val check = nonEmpty.take(20).let { if (partial && it.size == nonEmpty.size) it.dropLast(1) else it }
            ndjson = check.size >= 2 && check.all { ln -> val t = ln.trim(); (t.startsWith("{") || t.startsWith("[")) && scan(t, 0, t.length, 0).let { it.balanced && it.end == t.length } }
        }
        if (e == "ndjson" || e == "jsonl") { if (c0 == '{' || c0 == '[') ndjson = true }
        if (partial) f.warn("Only the first ${GsKit.mib(raw.size)} was read")
        val lineCount = lines.size - (if (text.endsWith("\n")) 1 else 0)
        if (ndjson) {
            val firstLine = nonEmpty[0].trim()
            val sc = scan(firstLine, 0, firstLine.length, 40)
            f.add("Shape", "Layout", "one JSON value per line (NDJSON / JSON Lines)")
            f.add("Shape", if (partial) "Lines in window" else "Lines", lineCount)
            f.add("Shape", "Non-empty records" + if (partial) " in window" else "", nonEmpty.size)
            f.add("Shape", "First record kind", if (firstLine.startsWith("{")) "object" else "array")
            if (sc.keys.isNotEmpty()) f.add("Keys", "Keys of first record", sc.keys.joinToString(", "))
            val bad = nonEmpty.take(1000).count { ln -> val t = ln.trim(); !scan(t, 0, t.length, 0).let { it.balanced && it.end == t.length } }
            if (bad > 0) f.warn("$bad of the first ${minOf(1000, nonEmpty.size)} lines are not balanced single JSON values")
            InspectionResult("ndjson", "Newline-delimited JSON", "${nonEmpty.size} records", f.facts, f.warnings, text = text.take(2000))
        } else {
            val shape = when (c0) { '{' -> "object"; '[' -> "array"; '"' -> "string"; 't', 'f' -> "boolean"; 'n' -> "null"; else -> "number" }
            f.add("Shape", "Top-level value", shape)
            var summary = shape
            if (c0 == '{' || c0 == '[') {
                val sc = scan(text, start, text.length, 40)
                val n = topCountFor(text, start, text.length, c0 == '{')
                f.add("Shape", if (c0 == '{') "Top-level members" + (if (!sc.balanced) " (at least)" else "") else "Array elements" + (if (!sc.balanced) " (at least)" else ""), n)
                f.add("Shape", "Maximum nesting depth" + (if (!sc.balanced) " (so far)" else ""), sc.depthMax)
                f.add("Shape", "Brackets balanced", if (sc.balanced) "yes" else if (partial) "not determined (read window ended)" else "no")
                if (c0 == '{' && sc.keys.isNotEmpty()) {
                    f.add("Keys", "Top-level keys (sample)", sc.keys.joinToString(", "))
                    for ((i, k) in sc.keys.withIndex().take(20)) f.add("Key types", k, sc.kinds[i])
                }
                if (c0 == '[') {
                    val el = text.indexOfAny(charArrayOf('{', '[', '"', '-', 't', 'f', 'n', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'), start + 1)
                    if (el >= 0) f.add("Shape", "First element kind", kindOf(text[el]))
                }
                if (!sc.balanced && !partial) f.warn("Brackets are not balanced; the file may be truncated or invalid")
                summary = if (c0 == '{') "object, $n members" else "array, $n elements"
            }
            f.add("Shape", "Line count" + if (partial) " (in window)" else "", lineCount)
            InspectionResult("json", "JSON document", summary, f.facts, f.warnings, text = text.take(2000))
        }
    }
}

/** XML: declaration, encoding, root element, namespaces, bounded depth/elements sample. */
object GisScienceXmlInspector : FormatInspector {
    override val formatIds = setOf("xml")
    private const val MAX_TAGS = 400_000

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val raw = rd.head(TEXT_WINDOW)
        if (raw.isEmpty()) return@guard null
        val text = GsKit.decodeText(raw)
        val f = GsFacts()
        val t0 = text.trimStart()
        if (!t0.startsWith("<")) return@guard null
        val partial = source.size > raw.size
        var i = text.length - t0.length
        var decl: String? = null
        if (text.startsWith("<?xml", i)) {
            val end = text.indexOf("?>", i)
            if (end > 0) { decl = text.substring(i, end + 2); i = end + 2 }
        }
        if (decl != null) {
            f.add("Declaration", "XML declaration", GsKit.clip(decl, 200))
            GsKit.attr(decl, "version")?.let { f.add("Declaration", "Version", it) }
            GsKit.attr(decl, "encoding")?.let { f.add("Declaration", "Encoding", it) }
            GsKit.attr(decl, "standalone")?.let { f.add("Declaration", "Standalone", it) }
        } else f.add("Declaration", "XML declaration", "absent")
        val bom = when {
            raw.size >= 3 && (raw[0].toInt() and 0xFF) == 0xEF && (raw[1].toInt() and 0xFF) == 0xBB -> "UTF-8 BOM"
            raw.size >= 2 && (raw[0].toInt() and 0xFF) == 0xFE && (raw[1].toInt() and 0xFF) == 0xFF -> "UTF-16 BE BOM"
            raw.size >= 2 && (raw[0].toInt() and 0xFF) == 0xFF && (raw[1].toInt() and 0xFF) == 0xFE -> "UTF-16 LE BOM"
            else -> null
        }
        f.add("Declaration", "Byte order mark", bom)
        var depth = 0; var maxDepth = 0; var elements = 0; var root: String? = null; var rootTag: String? = null
        var doctype: String? = null; var pis = 0; var comments = 0
        val childCounts = java.util.TreeMap<String, Int>()
        val stack = ArrayDeque<String>()
        var tags = 0
        var rootClosed = false
        loop@ while (i < text.length && tags < MAX_TAGS) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            tags++
            when {
                text.startsWith("<!--", lt) -> { val e = text.indexOf("-->", lt + 4); comments++; if (e < 0) break@loop; i = e + 3 }
                text.startsWith("<![CDATA[", lt) -> { val e = text.indexOf("]]>", lt); if (e < 0) break@loop; i = e + 3 }
                text.startsWith("<?", lt) -> { val e = text.indexOf("?>", lt); pis++; if (e < 0) break@loop; i = e + 2 }
                text.startsWith("<!DOCTYPE", lt) -> {
                    // skip internal subset [ ... ] if present
                    var j = lt + 9; var br = 0; var q = 0.toChar()
                    while (j < text.length) {
                        val c = text[j]
                        if (q != 0.toChar()) { if (c == q) q = 0.toChar() }
                        else if (c == '"' || c == '\'') q = c
                        else if (c == '[') br++ else if (c == ']') br-- else if (c == '>' && br <= 0) break
                        j++
                    }
                    if (j >= text.length) break@loop
                    doctype = text.substring(lt + 9, minOf(j, lt + 209)).trim().substringBefore(' ').substringBefore('[')
                    i = j + 1
                }
                text.startsWith("</", lt) -> {
                    val e = text.indexOf('>', lt); if (e < 0) break@loop
                    if (stack.isNotEmpty()) stack.removeLast()
                    depth = maxOf(0, depth - 1); if (depth == 0 && root != null) rootClosed = true
                    i = e + 1
                }
                else -> {
                    // start tag; find the closing '>' honouring quotes
                    var j = lt + 1; var q = 0.toChar()
                    while (j < text.length) {
                        val c = text[j]
                        if (q != 0.toChar()) { if (c == q) q = 0.toChar() } else if (c == '"' || c == '\'') q = c else if (c == '>') break
                        j++
                    }
                    if (j >= text.length) break@loop
                    val inner = text.substring(lt + 1, j)
                    val name = inner.takeWhile { !it.isWhitespace() && it != '/' && it != '>' }
                    if (name.isEmpty()) { i = lt + 1; continue@loop }
                    val selfClose = inner.endsWith("/")
                    elements++
                    if (root == null) { root = name; rootTag = inner }
                    else if (depth == 1) childCounts[name] = (childCounts[name] ?: 0) + 1
                    if (!selfClose) { depth++; if (depth > maxDepth) maxDepth = depth; if (stack.size < 2000) stack.addLast(name) }
                    else { if (depth + 1 > maxDepth) maxDepth = depth + 1; if (root == name && depth == 0) rootClosed = true }
                    i = j + 1
                }
            }
        }
        if (root == null) return@guard null
        f.add("Document", "Root element", root)
        doctype?.let { f.add("Document", "DOCTYPE", it) }
        val ns = ArrayList<String>()
        for (m in Regex("""xmlns(?::([\w.\-]+))?\s*=\s*(?:"([^"]*)"|'([^']*)')""").findAll(rootTag ?: "")) {
            if (ns.size < 30) ns.add((m.groupValues[1].ifEmpty { "(default)" }) + " = " + GsKit.clip(m.groupValues[2].ifEmpty { m.groupValues[3] }, 200))
        }
        for (n in ns) f.add("Namespaces", "xmlns", n)
        f.add("Sample", if (partial) "Elements in window" else "Elements", elements)
        f.add("Sample", "Maximum depth" + if (partial) " (in window)" else "", maxDepth)
        f.add("Sample", "Processing instructions", pis.takeIf { it > 0 })
        f.add("Sample", "Comments", comments.takeIf { it > 0 })
        if (childCounts.isNotEmpty()) f.add("Sample", "Children of root", childCounts.entries.sortedByDescending { it.value }.take(10).joinToString(", ") { "${it.key} x${it.value}" })
        if (!partial && !rootClosed && tags < MAX_TAGS) f.warn("Root element is never closed; the file may be truncated or malformed")
        if (partial) f.warn("Only the first ${GsKit.mib(raw.size)} was read")
        if (tags >= MAX_TAGS) f.warn("Tag scan limit reached; counts are lower bounds")
        InspectionResult("xml", "XML document", "root <$root>, depth $maxDepth", f.facts, f.warnings)
    }
}

/** YAML: top-level key sample by line scan (no full parse). */
object GisScienceYamlInspector : FormatInspector {
    override val formatIds = setOf("yaml")
    private val KEY = Regex("""^(?:"([^"]*)"|'([^']*)'|([^\s#\-"'\[\]{}&*!|>%@`][^#]*?))\s*:(?:\s|$)""")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val raw = rd.head(1024 * 1024)
        if (raw.isEmpty() || looksBinary(raw)) return@guard null
        val text = GsKit.decodeText(raw)
        val f = GsFacts()
        var docs = 0; var seqTop = false; val keys = ArrayList<String>(); var directive: String? = null
        var sawContent = false; var inBlock = false; var blockIndent = 0
        var lines = 0
        for (line in text.lineSequence()) {
            lines++; if (lines > 200_000) break
            if (line.startsWith("%YAML")) { directive = line.trim(); continue }
            if (line.startsWith("---")) { docs++; continue }
            if (line.startsWith("...")) continue
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val indent = line.length - line.trimStart().length
            if (inBlock) { if (indent > blockIndent) continue else inBlock = false }
            if (indent == 0) {
                sawContent = true
                if (line.startsWith("- ") || line == "-") { seqTop = true; continue }
                val m = if (line.length > 4096) null else KEY.find(line)
                if (m != null) {
                    val k = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3].trim() }
                    if (keys.size < 500 && k !in keys) keys.add(k)
                    val rest = line.substring(m.range.last + 1).trim()
                    if (rest.startsWith("|") || rest.startsWith(">")) { inBlock = true; blockIndent = 0 }
                }
            }
        }
        if (!sawContent) return@guard null
        if (keys.isEmpty() && !seqTop) return@guard null
        if (docs == 0) docs = 1
        directive?.let { f.add("Document", "Directive", it) }
        f.add("Document", "Documents (approx.)", docs)
        f.add("Document", "Top-level shape", if (seqTop && keys.isEmpty()) "sequence" else "mapping")
        f.add("Keys", "Top-level key count" + if (keys.size >= 500) " (capped)" else "", keys.size)
        f.add("Keys", "Top-level keys (sample)", keys.take(30).joinToString(", "))
        if (source.size > raw.size) f.warn("Only the first ${GsKit.mib(raw.size)} was read")
        InspectionResult("yaml", "YAML document", "${keys.size} top-level keys", f.facts, f.warnings, text = text.take(2000))
    }
}

/** TOML: top-level keys and table headers by line scan (no full parse). */
object GisScienceTomlInspector : FormatInspector {
    override val formatIds = setOf("toml")
    private val KV = Regex("""^([A-Za-z0-9_\-]+(?:\s*\.\s*[A-Za-z0-9_\-]+)*|"[^"]*"|'[^']*')\s*=""")
    private val TABLE = Regex("""^\[(?!\[)\s*([^\]]+?)\s*\]\s*(?:#.*)?$""")
    private val AOT = Regex("""^\[\[\s*([^\]]+?)\s*\]\]\s*(?:#.*)?$""")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val raw = rd.head(1024 * 1024)
        if (raw.isEmpty() || looksBinary(raw)) return@guard null
        val text = GsKit.decodeText(raw)
        val f = GsFacts()
        val keys = ArrayList<String>(); val tables = ArrayList<String>(); val aots = java.util.TreeMap<String, Int>()
        var inTables = false; var multi: String? = null; var arrDepth = 0; var lines = 0
        for (line0 in text.lineSequence()) {
            lines++; if (lines > 200_000) break
            val line = line0.trim()
            if (line.length > 4096 && multi == null && arrDepth == 0) continue
            if (multi != null) { if (line0.contains(multi)) multi = null; continue }
            if (arrDepth > 0) {
                arrDepth += line.count { it == '[' } - line.count { it == ']' }
                if (arrDepth < 0) arrDepth = 0
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            val am = AOT.find(line)
            if (am != null) { inTables = true; aots[am.groupValues[1]] = (aots[am.groupValues[1]] ?: 0) + 1; continue }
            val tm = TABLE.find(line)
            if (tm != null) { inTables = true; if (tables.size < 500) tables.add(tm.groupValues[1]); continue }
            val m = KV.find(line) ?: continue
            val v = line.substring(m.range.last + 1).trim()
            for (q in listOf("\"\"\"", "'''")) if (v.startsWith(q) && v.indexOf(q, 3) < 0) multi = q
            if (v.startsWith("[")) arrDepth = maxOf(0, v.count { it == '[' } - v.count { it == ']' })
            if (!inTables && keys.size < 500) keys.add(m.groupValues[1].trim())
        }
        if (keys.isEmpty() && tables.isEmpty() && aots.isEmpty()) return@guard null
        f.add("Keys", "Top-level keys", keys.size)
        f.add("Keys", "Top-level keys (sample)", keys.take(30).joinToString(", "))
        f.add("Tables", "Tables", tables.size)
        f.add("Tables", "Table names (sample)", tables.take(30).joinToString(", "))
        if (aots.isNotEmpty()) f.add("Tables", "Arrays of tables", aots.entries.take(30).joinToString(", ") { "${it.key} x${it.value}" })
        if (source.size > raw.size) f.warn("Only the first ${GsKit.mib(raw.size)} was read")
        InspectionResult("toml", "TOML document", "${keys.size} top-level keys, ${tables.size} tables", f.facts, f.warnings, text = text.take(2000))
    }
}
