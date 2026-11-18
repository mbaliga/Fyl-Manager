package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** A non-string STEP parameter token ($, *, #12, .T., 3.5, ...). */
internal class CadStepTok(val t: String)

/** Parser for STEP/Part 21 parameter lists. Bounded: depth <= 8, <= 2000 elements per list. */
internal object CadStepParams {
    private class Cur(val s: String) { var i = 0 }

    /** Parses the first parenthesised argument list found in [s]. */
    fun parseCall(s: String): List<Any?> {
        val p = s.indexOf('(')
        if (p < 0) return emptyList()
        val c = Cur(s); c.i = p
        return list(c, 0)
    }

    private fun list(c: Cur, depth: Int): List<Any?> {
        val s = c.s
        val out = ArrayList<Any?>()
        c.i++ // '('
        var guard = 0
        while (c.i < s.length && guard++ < 4000 && out.size < 2000) {
            val ch = s[c.i]
            when {
                ch == ' ' || ch == ',' -> c.i++
                ch == ')' -> { c.i++; return out }
                ch == '\'' -> out.add(str(c))
                ch == '(' -> if (depth < 8) out.add(list(c, depth + 1)) else { c.i = s.length }
                else -> {
                    val st = c.i
                    while (c.i < s.length && s[c.i] != ',' && s[c.i] != ')' && s[c.i] != '(') c.i++
                    val tok = s.substring(st, c.i).trim()
                    if (c.i < s.length && s[c.i] == '(' && depth < 8) out.add(list(c, depth + 1)) // typed value NAME(...)
                    else out.add(CadStepTok(tok))
                }
            }
        }
        return out
    }

    private fun str(c: Cur): String {
        val s = c.s
        val sb = StringBuilder()
        c.i++
        while (c.i < s.length) {
            val ch = s[c.i]
            if (ch == '\'') {
                if (c.i + 1 < s.length && s[c.i + 1] == '\'') { sb.append('\''); c.i += 2; continue }
                c.i++
                break
            }
            sb.append(ch); c.i++
        }
        return decode(sb.toString())
    }

    /** Decodes \X2\hhhh\X0\ (UTF-16), \X\hh (Latin-1) and \\ escapes. Anything else is left as is. */
    fun decode(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s.startsWith("\\X2\\", i)) {
                val e = s.indexOf("\\X0\\", i + 4)
                if (e > 0) {
                    var k = i + 4
                    while (k + 4 <= e) {
                        val v = s.substring(k, k + 4).toIntOrNull(16)
                        if (v != null) sb.append(v.toChar())
                        k += 4
                    }
                    i = e + 4
                    continue
                }
            } else if (s.startsWith("\\X\\", i) && i + 5 <= s.length) {
                val v = s.substring(i + 3, i + 5).toIntOrNull(16)
                if (v != null) { sb.append(v.toChar()); i += 5; continue }
            } else if (s.startsWith("\\\\", i)) { sb.append('\\'); i += 2; continue }
            sb.append(s[i]); i++
        }
        return sb.toString()
    }

    fun strings(v: Any?): List<String> = when (v) {
        is String -> listOf(v)
        is List<*> -> v.flatMap { strings(it) }
        else -> emptyList()
    }

    fun first(v: Any?): String? = strings(v).firstOrNull()
}

/** ISO 10303-21 (STEP physical file) inspector. */
object CadStepInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("step", "stpz")

    private val siRe = Regex("SI_UNIT\\(\\s*(?:\\.([A-Z_]+)\\.|\\$)\\s*,\\s*\\.([A-Z_]+)\\.")
    private val convRe = Regex("CONVERSION_BASED_UNIT\\(\\s*'((?:[^']|'')*)'")
    private const val MAX_TYPES = 5000

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        run(source)
    } catch (t: Throwable) { null }

    private fun run(source: ByteSource): InspectionResult? {
        val head4 = CadIo.readUpTo(source, 0, 4)
        if (head4.size < 4) return null
        val isGz = Bytes.u8(head4, 0) == 0x1F && Bytes.u8(head4, 1) == 0x8B
        val isZip = Bytes.startsWith(head4, "PK")
        val head = when {
            isZip -> head4
            isGz -> CadIo.readUpTo(source, 0, 4 * 1024 * 1024)
            else -> head4 + CadIo.readUpTo(source, 4, FormatInspector.MAX_READ_BYTES - 4)
        }
        val f = CadFacts()
        var data = head
        var container: String? = null
        var truncated = !isZip && source.size > head.size
        if (isGz) {
            container = "gzip (stpz)"
            val comp = if (head.size > 4 * 1024 * 1024) head.copyOf(4 * 1024 * 1024) else head
            val out = gunzip(comp, FormatInspector.MAX_READ_BYTES)
            data = out.first
            truncated = out.second || source.size > comp.size
            if (data.isEmpty()) {
                f.warn("gzip stream could not be decompressed")
                f.add("Container", "Container", container)
                return InspectionResult("step", "STEP (compressed)", "gzip-wrapped STEP file", f.list, f.warnings)
            }
        } else if (isZip) {
            container = "ZIP"
            val zip = CadZip.open(source)
            val e = zip?.entries?.firstOrNull { val n = it.name.lowercase(); n.endsWith(".stp") || n.endsWith(".step") || n.endsWith(".p21") }
            if (zip == null || e == null) {
                f.add("Container", "Container", container)
                f.warn("ZIP-wrapped STEP: no .stp/.step entry could be located")
                return InspectionResult("step", "STEP (zip-wrapped)", "ZIP-wrapped STEP file", f.list, f.warnings)
            }
            val out = zip.extract(e, FormatInspector.MAX_READ_BYTES) ?: return null
            data = out.data
            truncated = out.truncated
            container = "ZIP (entry ${e.name})"
        }
        val start = CadIo.skipBom(data)
        val text = CadIo.latin1(data, start)
        if (!text.trimStart().startsWith("ISO-10303-21")) return null
        val scan = Scan(text)
        scan.run()
        if (!scan.sawHeaderTag) return null

        f.add("File", "Container", container)
        // Header
        val h = scan.header
        val desc = CadStepParams.strings(h["FILE_DESCRIPTION"]?.getOrNull(0))
        f.add("Header", "Description", desc.joinToString("; "))
        f.add("Header", "Implementation level", CadStepParams.first(h["FILE_DESCRIPTION"]?.getOrNull(1)))
        val fn = h["FILE_NAME"]
        f.add("Header", "Name", CadStepParams.first(fn?.getOrNull(0)))
        f.add("Header", "Timestamp", CadStepParams.first(fn?.getOrNull(1)))
        f.add("Header", "Author", CadStepParams.strings(fn?.getOrNull(2)).filter { it.isNotBlank() }.joinToString("; "))
        f.add("Header", "Organization", CadStepParams.strings(fn?.getOrNull(3)).filter { it.isNotBlank() }.joinToString("; "))
        f.add("Header", "Preprocessor", CadStepParams.first(fn?.getOrNull(4)))
        f.add("Header", "Originating system", CadStepParams.first(fn?.getOrNull(5)))
        f.add("Header", "Authorization", CadStepParams.first(fn?.getOrNull(6)))
        val schemas = CadStepParams.strings(h["FILE_SCHEMA"]?.getOrNull(0))
        f.add("Header", "Schema", schemas.joinToString("; "))
        val friendly = schemas.mapNotNull { friendlySchema(it) }.distinct()
        f.add("Header", "Application protocol", friendly.joinToString(", "))

        // Data
        f.add("Data", "Entities", CadIo.count(scan.entities))
        f.add("Data", "Distinct entity types", scan.types.size.toString())
        val top = scan.types.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(10)
        f.add("Data", "Top entity types", top.joinToString(", ") { "${it.key} (${it.value})" })
        f.add("Data", "Products", if (scan.productCount > 0) scan.productCount.toString() else null)
        f.add("Data", "Product names", scan.products.joinToString("; "))
        f.add("Units", "Length unit", scan.lengthUnits.joinToString("; "))
        f.add("Units", "Angle unit", scan.angleUnits.joinToString("; "))
        val asm = scan.types["NEXT_ASSEMBLY_USAGE_OCCURRENCE"] ?: 0
        f.add("Features", "Assembly", if (asm > 0) "yes ($asm NEXT_ASSEMBLY_USAGE_OCCURRENCE)" else "no assembly relationships found")
        val colours = (scan.types["COLOUR_RGB"] ?: 0) + (scan.types["DRAUGHTING_PRE_DEFINED_COLOUR"] ?: 0)
        f.add("Features", "Colours", if (colours > 0) "yes ($colours colour entities)" else "no colour entities found")
        val pmi = scan.types.entries.filter { isPmi(it.key) }.sumOf { it.value }
        f.add("Features", "PMI", if (pmi > 0) "yes ($pmi dimension/tolerance/datum entities)" else "no PMI entities found")

        if (truncated) f.warn("Only the first ${Bytes.humanSize(data.size.toLong())} were scanned; counts are lower bounds")
        else if (!scan.ended) f.warn("END-ISO-10303-21; trailer missing (file may be truncated)")
        if (scan.pendingTail) f.warn("Last statement is not terminated by ';'")
        if (h["FILE_SCHEMA"] == null) f.warn("No FILE_SCHEMA in header")
        if (h["FILE_NAME"] == null) f.warn("No FILE_NAME in header")
        if (h["FILE_DESCRIPTION"] == null) f.warn("No FILE_DESCRIPTION in header")

        val title = scan.products.firstOrNull() ?: CadStepParams.first(fn?.getOrNull(0))?.takeIf { it.isNotBlank() } ?: "STEP file"
        val ap = friendly.firstOrNull()
        val summary = buildString {
            append("STEP")
            if (ap != null) append(" ").append(ap)
            append(", ").append(CadIo.count(scan.entities)).append(" entities")
            if (scan.productCount > 0) append(", ").append(scan.productCount).append(" product(s)")
        }
        return InspectionResult("step", title, summary, f.list, f.warnings, if (desc.isNotEmpty()) CadIo.clip(desc.joinToString("\n"), FormatInspector.MAX_TEXT_CHARS) else null)
    }

    private fun isPmi(t: String) =
        t.startsWith("GEOMETRIC_TOLERANCE") || t == "DIMENSIONAL_SIZE" || t == "DIMENSIONAL_LOCATION" ||
            t == "DATUM" || t == "DATUM_FEATURE" || t == "DRAUGHTING_CALLOUT" || t == "SHAPE_DIMENSION_REPRESENTATION"

    fun friendlySchema(name: String): String? {
        val u = name.substringBefore('{').trim().uppercase()
        return when {
            u.contains("AP242") -> "AP242 (Managed Model Based 3D Engineering)"
            u.startsWith("AP203_CONFIGURATION_CONTROLLED_3D_DESIGN") -> "AP203 edition 2"
            u.contains("AP203") -> "AP203 (Configuration Controlled Design)"
            u.contains("AP214E3") -> "AP214 edition 3 (Automotive Design)"
            u.contains("AP214") -> "AP214 (Automotive Design)"
            u.contains("AP238") || u.startsWith("INTEGRATED_CNC_SCHEMA") -> "AP238 (STEP-NC)"
            u.contains("AP209") -> "AP209 (Multidisciplinary Analysis and Design)"
            u.contains("CONFIG_CONTROL_DESIGN") -> "AP203 (Configuration Controlled Design)"
            u.contains("AUTOMOTIVE_DESIGN") -> "AP214 (Automotive Design)"
            else -> null
        }
    }

    private fun gunzip(comp: ByteArray, max: Int): Pair<ByteArray, Boolean> {
        val bos = java.io.ByteArrayOutputStream()
        var trunc = false
        try {
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(comp)).use { gz ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = gz.read(buf)
                    if (n < 0) break
                    val room = max - bos.size()
                    if (n >= room) { bos.write(buf, 0, room); trunc = true; break }
                    bos.write(buf, 0, n)
                }
            }
        } catch (t: Throwable) { trunc = true }
        return bos.toByteArray() to trunc
    }

    private class Scan(val text: String) {
        var sawHeaderTag = false
        var ended = false
        var pendingTail = false
        var section = 0 // 0 none, 1 header, 2 data, 3 other
        val header = HashMap<String, List<Any?>>()
        var entities = 0L
        val types = HashMap<String, Int>()
        var productCount = 0
        val products = ArrayList<String>()
        val lengthUnits = LinkedHashSet<String>()
        val angleUnits = LinkedHashSet<String>()

        fun run() {
            val n = text.length
            var i = 0
            val sb = StringBuilder()
            var inStr = false
            while (i < n) {
                val c = text[i]
                if (!inStr && c == '/' && i + 1 < n && text[i + 1] == '*') {
                    val e = text.indexOf("*/", i + 2)
                    i = if (e < 0) n else e + 2
                    continue
                }
                if (c == '\'') inStr = !inStr
                if (!inStr && c == ';') {
                    statement(sb.toString().trim())
                    sb.setLength(0)
                    i++
                    continue
                }
                val cap = if (section == 2) 700 else 12000
                if (sb.length < cap) sb.append(if (c == '\n' || c == '\r' || c == '\t') ' ' else c)
                i++
            }
            if (sb.isNotBlank()) pendingTail = true
        }

        private fun ident(s: String): String {
            var j = 0
            while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '-')) j++
            return s.substring(0, j).uppercase()
        }

        private fun statement(s: String) {
            if (s.isEmpty()) return
            if (section == 2) {
                if (s.startsWith("ENDSEC")) { section = 0; return }
                entity(s)
                return
            }
            val id = ident(s)
            when {
                id == "ISO-10303-21" -> sawHeaderTag = true
                id == "END-ISO-10303-21" -> ended = true
                id == "HEADER" -> section = 1
                id == "DATA" -> section = 2
                id == "ENDSEC" -> section = 0
                section == 1 -> { if (header.size < 64) header[id] = CadStepParams.parseCall(s) }
                section == 0 -> section = 3.takeIf { id == "ANCHOR" || id == "REFERENCE" || id == "SIGNATURE" } ?: 0
            }
        }

        private fun entity(s: String) {
            if (s[0] != '#') return
            var j = 1
            while (j < s.length && s[j].isDigit()) j++
            while (j < s.length && s[j] == ' ') j++
            if (j >= s.length || s[j] != '=') return
            j++
            while (j < s.length && s[j] == ' ') j++
            val complex = j < s.length && s[j] == '('
            if (complex) { j++; while (j < s.length && s[j] == ' ') j++ }
            val st = j
            while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
            if (j == st) return
            val type = s.substring(st, j).uppercase()
            entities++
            if (types.containsKey(type) || types.size < MAX_TYPES) types[type] = (types[type] ?: 0) + 1
            if (!complex && type == "PRODUCT") {
                productCount++
                val args = CadStepParams.parseCall(s.substring(j - type.length))
                val nm = (args.getOrNull(1) as? String)?.takeIf { it.isNotBlank() } ?: (args.getOrNull(0) as? String)
                if (nm != null && products.size < 30 && nm !in products) products.add(nm)
            } else if (complex && (s.contains("SI_UNIT(") || s.contains("CONVERSION_BASED_UNIT("))) {
                val isLen = s.contains("LENGTH_UNIT")
                val isAng = s.contains("PLANE_ANGLE_UNIT")
                if (!isLen && !isAng) return
                val si = siRe.find(s)
                val desc = if (si != null) {
                    val prefix = si.groupValues[1].lowercase()
                    prefix + si.groupValues[2].lowercase() + " (SI)"
                } else {
                    convRe.find(s)?.let { it.groupValues[1].replace("''", "'").lowercase() + " (conversion-based)" }
                }
                if (desc != null) {
                    val set = if (isLen) lengthUnits else angleUnits
                    if (set.size < 8) set.add(desc)
                }
            }
        }
    }
}
