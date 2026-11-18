package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** WebAssembly binary modules (.wasm). */
object BinaryWasmInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("wasm")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = binSafe { run(source) }

    private val SECTION_NAMES = mapOf(
        1 to "Type", 2 to "Import", 3 to "Function", 4 to "Table", 5 to "Memory", 6 to "Global", 7 to "Export",
        8 to "Start", 9 to "Element", 10 to "Code", 11 to "Data", 12 to "Data count", 13 to "Tag",
    )
    private const val MAX_SECTIONS = 2048
    private const val MAX_PARSE = 1 shl 20

    /** Cursor over a byte array reading LEB128 values; fail() once anything is out of range. */
    private class Cur(val b: ByteArray) {
        var p = 0
        var ok = true
        fun u32(): Long {
            var result = 0L
            var shift = 0
            for (i in 0 until 5) {
                if (p >= b.size) { ok = false; return 0 }
                val v = b[p++].toInt() and 0xFF
                result = result or ((v and 0x7F).toLong() shl shift)
                if (v and 0x80 == 0) return result and 0xFFFFFFFFL
                shift += 7
            }
            ok = false
            return 0
        }
        fun u64(): Long {
            var result = 0L
            var shift = 0
            for (i in 0 until 10) {
                if (p >= b.size) { ok = false; return 0 }
                val v = b[p++].toInt() and 0xFF
                if (shift < 64) result = result or ((v and 0x7F).toLong() shl shift)
                if (v and 0x80 == 0) return result
                shift += 7
            }
            ok = false
            return 0
        }
        fun byte(): Int { if (p >= b.size) { ok = false; return 0 }; return b[p++].toInt() and 0xFF }
        fun name(): String {
            val n = u32()
            if (!ok || n > b.size - p) { ok = false; return "" }
            val s = BinFmt.clean(String(b, p, minOf(n.toInt(), 512), Charsets.UTF_8))
            p += n.toInt()
            return s
        }
        fun limits() {
            val f = byte()
            u64()
            if (f and 1 != 0) u64()
        }
    }

    private fun run(source: ByteSource): InspectionResult? {
        val rd = BinReader(source)
        val h = rd.read(0, 8)
        if (h.size < 8 || Bytes.u32le(h, 0) != 0x6D736100L) return null
        val version = Bytes.u32le(h, 4)
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        if (version == 0x0001000DL) {
            facts += Fact("Version field", "0x0001000d (component model: version 13, layer 1)", "Header")
            return InspectionResult("wasm", "WebAssembly component", "WebAssembly component-model binary; sections not parsed", facts, warnings)
        }
        facts += Fact("Version", version.toString(), "Header")
        if (version != 1L) {
            warnings += "Unrecognised WebAssembly version $version; sections not parsed"
            return InspectionResult("wasm", "WebAssembly module (version $version)", null, facts, warnings)
        }

        class Sec(val id: Int, val name: String?, val size: Long, val off: Long)
        val secs = ArrayList<Sec>()
        var off = 8L
        var truncated = false
        val counts = HashMap<Int, Long>()
        var importedFuncs = 0L
        var startFunc = -1L
        val imports = ArrayList<String>()
        val exports = ArrayList<String>()
        var importKinds = LongArray(5)
        var importsParsed = false
        var exportKinds = LongArray(5)

        while (off < rd.size && secs.size < MAX_SECTIONS) {
            val hd = rd.read(off, 6)
            if (hd.size < 2) { truncated = true; break }
            val c = Cur(hd)
            val id = c.byte()
            val size = c.u32()
            if (!c.ok) { warnings += "Malformed section header at offset ${BinFmt.hex(off)}"; break }
            val contentOff = off + c.p
            var sz = size
            if (size > rd.size - contentOff) {
                warnings += "Section ${SECTION_NAMES[id] ?: id} declares $size bytes but only ${rd.size - contentOff} remain"
                sz = rd.size - contentOff
                truncated = true
            }
            var name: String? = null
            if (id == 0 && sz > 0) {
                name = Cur(rd.read(contentOff, minOf(sz, 600L))).let { val n = it.name(); if (it.ok) n else null }
            }
            secs += Sec(id, name, sz, contentOff)

            if (id != 0 && sz > 0) {
                val body = rd.read(contentOff, minOf(sz, MAX_PARSE.toLong()))
                val cur = Cur(body)
                val cnt = cur.u32()
                if (cur.ok) {
                    counts[id] = cnt
                    when (id) {
                        2 -> if (sz <= MAX_PARSE) importsParsed = parseImports(cur, cnt, imports, importKinds)
                        7 -> parseExports(cur, cnt, exports, exportKinds)
                    }
                }
                if (id == 8) { val sc = Cur(body); val f = sc.u32(); if (sc.ok) startFunc = f }
            }
            if (truncated) break
            off = contentOff + sz
        }
        if (secs.size >= MAX_SECTIONS && off < rd.size) warnings += "Only the first $MAX_SECTIONS sections were read"

        facts += Fact("Sections", secs.size.toString(), "Sections")
        for (s in secs) {
            val label = if (s.id == 0) "Custom section" else (SECTION_NAMES[s.id]?.let { "$it section" } ?: "Unknown section (id ${s.id})")
            facts += Fact(label + (s.name?.let { ": $it" } ?: ""), "${s.size} bytes at offset ${BinFmt.hex(s.off)}", "Sections")
        }
        fun cnt(id: Int, label: String) { counts[id]?.let { facts += Fact(label, it.toString(), "Counts") } }
        cnt(1, "Types")
        if (importsParsed) {
            facts += Fact("Imports", (counts[2] ?: 0).toString(), "Counts")
            facts += Fact("Imported functions", importKinds[0].toString(), "Counts")
            importedFuncs = importKinds[0]
        } else cnt(2, "Imports")
        counts[3]?.let {
            facts += Fact("Defined functions", it.toString(), "Counts")
            if (importsParsed || counts[2] == null) facts += Fact("Total functions", (it + importedFuncs).toString(), "Counts")
        }
        cnt(4, "Tables"); cnt(5, "Memories"); cnt(6, "Globals"); cnt(7, "Exports"); cnt(9, "Element segments")
        cnt(10, "Code bodies"); cnt(11, "Data segments"); cnt(12, "Data count"); cnt(13, "Tags")
        if (startFunc >= 0) facts += Fact("Start function index", startFunc.toString(), "Counts")
        val kindNames = arrayOf("function", "table", "memory", "global", "tag")
        for (s in imports.take(100)) facts += Fact("Import", s, "Imports")
        if (imports.size > 100) facts += Fact("Import", "... (+${imports.size - 100} more)", "Imports")
        for (s in exports.take(100)) facts += Fact("Export", s, "Exports")
        if (exports.size > 100) facts += Fact("Export", "... (+${exports.size - 100} more)", "Exports")
        if (truncated) warnings += "The file ends in the middle of a section"

        val title = "WebAssembly module (version 1)"
        val summary = buildString {
            append(title).append(", ").append(secs.size).append(" sections")
            counts[7]?.let { append(", ").append(it).append(" exports") }
            counts[3]?.let { append(", ").append(it).append(" functions") }
        }
        return InspectionResult("wasm", title, summary, facts, warnings)
    }

    private fun parseImports(c: Cur, n: Long, out: MutableList<String>, kinds: LongArray): Boolean {
        val kindNames = arrayOf("func", "table", "memory", "global", "tag")
        val limit = minOf(n, 100000L)
        var i = 0L
        while (i < limit) {
            val mod = c.name(); val fld = c.name()
            val kind = c.byte()
            if (!c.ok) return false
            when (kind) {
                0 -> c.u32()
                1 -> { c.byte(); c.limits() }
                2 -> c.limits()
                3 -> { c.byte(); c.byte() }
                4 -> { c.byte(); c.u32() }
                else -> return false
            }
            if (!c.ok) return false
            kinds[kind]++
            if (out.size < 1000) out += "$mod.$fld (${kindNames[kind]})"
            i++
        }
        return true
    }

    private fun parseExports(c: Cur, n: Long, out: MutableList<String>, kinds: LongArray) {
        val kindNames = arrayOf("function", "table", "memory", "global", "tag")
        val limit = minOf(n, 100000L)
        var i = 0L
        while (i < limit) {
            val nm = c.name(); val kind = c.byte(); val idx = c.u32()
            if (!c.ok || kind > 4) return
            kinds[kind]++
            if (out.size < 1000) out += "$nm (${kindNames[kind]} $idx)"
            i++
        }
    }
}
