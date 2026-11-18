package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Apache Parquet footer (Thrift compact protocol FileMetaData). */
object GisScienceParquetInspector : FormatInspector {
    override val formatIds = setOf("parquet")
    private const val MAX_FOOTER = 4 * 1024 * 1024

    /** Minimal, bounded Thrift compact reader. Any inconsistency sets [ok] to false. */
    private class Th(val b: ByteArray) {
        var p = 0
        var ok = true
        fun byte(): Int { if (p >= b.size) { ok = false; return 0 }; return b[p++].toInt() and 0xFF }
        fun varint(): Long {
            var r = 0L; var shift = 0
            while (true) {
                val x = byte(); if (!ok) return 0
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7; if (shift > 63) { ok = false; return 0 }
            }
        }
        fun zz(): Long { val v = varint(); return (v ushr 1) xor -(v and 1) }
        fun bin(): ByteArray {
            val n = varint()
            if (!ok || n < 0 || n > b.size - p) { ok = false; return ByteArray(0) }
            val out = b.copyOfRange(p, p + n.toInt()); p += n.toInt(); return out
        }
        fun str(): String = String(bin(), Charsets.UTF_8)
        /** Reads a field header; returns (id,type) or null at STOP/error. */
        var lastId = 0
        fun field(): IntArray? {
            val h = byte(); if (!ok || h == 0) return null
            val delta = h shr 4; val type = h and 0xF
            lastId = if (delta == 0) zz().toInt() else lastId + delta
            return if (ok) intArrayOf(lastId, type) else null
        }
        fun skip(type: Int, depth: Int = 0) {
            if (!ok) return
            if (depth > 24) { ok = false; return }
            when (type) {
                1, 2 -> {}
                3 -> byte()
                4, 5, 6 -> varint()
                7 -> { if (p + 8 > b.size) ok = false else p += 8 }
                8 -> bin()
                9, 10 -> {
                    val h = byte(); var n = (h shr 4).toLong(); val et = h and 0xF
                    if (n == 15L) n = varint()
                    if (n < 0 || n > b.size) { ok = false; return }
                    var i = 0L
                    while (i < n && ok) { if (et == 1 || et == 2) byte() else skip(et, depth + 1); i++ }
                }
                11 -> {
                    val n = varint()
                    if (n < 0 || n > b.size) { ok = false; return }
                    if (n > 0) { val kv = byte(); val kt = kv shr 4; val vt = kv and 0xF
                        var i = 0L; while (i < n && ok) { skip(kt, depth + 1); skip(vt, depth + 1); i++ } }
                }
                12 -> { val save = lastId; lastId = 0
                    while (ok) { val fh = field() ?: break; skip(fh[1], depth + 1) }
                    lastId = save }
                else -> ok = false
            }
        }
        fun listHeader(): LongArray? { // size, elemType
            val h = byte(); var n = (h shr 4).toLong(); val et = h and 0xF
            if (n == 15L) n = varint()
            if (!ok || n < 0 || n > b.size) { ok = false; return null }
            return longArrayOf(n, et.toLong())
        }
    }

    private class El(var name: String = "", var type: Int = -1, var typeLen: Int = -1, var rep: Int = -1, var children: Int = 0, var conv: Int = -1, var logical: String? = null)

    private val TYPES = arrayOf("BOOLEAN", "INT32", "INT64", "INT96", "FLOAT", "DOUBLE", "BYTE_ARRAY", "FIXED_LEN_BYTE_ARRAY")
    private val REPS = arrayOf("REQUIRED", "OPTIONAL", "REPEATED")
    private val CONV = arrayOf("UTF8", "MAP", "MAP_KEY_VALUE", "LIST", "ENUM", "DECIMAL", "DATE", "TIME_MILLIS", "TIME_MICROS",
        "TIMESTAMP_MILLIS", "TIMESTAMP_MICROS", "UINT_8", "UINT_16", "UINT_32", "UINT_64", "INT_8", "INT_16", "INT_32", "INT_64", "JSON", "BSON", "INTERVAL")
    private val LOGICAL = mapOf(1 to "STRING", 2 to "MAP", 3 to "LIST", 4 to "ENUM", 5 to "DECIMAL", 6 to "DATE", 7 to "TIME",
        8 to "TIMESTAMP", 10 to "INTEGER", 11 to "UNKNOWN", 12 to "JSON", 13 to "BSON", 14 to "UUID")

    private fun readElement(t: Th): El? {
        val e = El(); val outerId = t.lastId; t.lastId = 0
        while (t.ok) {
            val fh = t.field() ?: break
            when (fh[0]) {
                1 -> e.type = t.zz().toInt()
                2 -> e.typeLen = t.zz().toInt()
                3 -> e.rep = t.zz().toInt()
                4 -> e.name = t.str()
                5 -> e.children = t.zz().toInt()
                6 -> e.conv = t.zz().toInt()
                10 -> if (fh[1] == 12) {
                    val save = t.lastId; t.lastId = 0
                    val inner = t.field()
                    if (inner != null) { e.logical = LOGICAL[inner[0]]; t.skip(inner[1]); t.field() }
                    t.lastId = save
                } else t.skip(fh[1])
                else -> t.skip(fh[1])
            }
        }
        t.lastId = outerId
        return if (t.ok) e else null
    }

    private fun colType(e: El): String {
        if (e.children > 0 || e.type < 0) return "group"
        val base = TYPES.getOrNull(e.type) ?: "type${e.type}"
        val extra = e.logical ?: CONV.getOrNull(e.conv)
        return base + (if (e.type == 7 && e.typeLen > 0) "(${e.typeLen})" else "") + (if (extra != null) " [$extra]" else "")
    }

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val size = source.size
        if (size < 12) return@guard null
        val head = rd.read(0, 4)
        val tail = rd.read(size - 8, 8)
        if (!Bytes.startsWith(head, "PAR1") && !Bytes.startsWith(head, "PARE")) return@guard null
        val encrypted = Bytes.startsWith(tail, "PARE", 4)
        if (!Bytes.startsWith(tail, "PAR1", 4) && !encrypted) return@guard null
        val f = GsFacts()
        val footerLen = Bytes.u32le(tail, 0)
        f.add("Footer", "Footer length", "$footerLen bytes")
        if (footerLen + 12 > size) { f.warn("Footer length exceeds the file size"); return@guard InspectionResult("parquet", "Parquet file", null, f.facts, f.warnings) }
        if (encrypted) { f.warn("Encrypted footer (PARE): metadata not readable"); return@guard InspectionResult("parquet", "Parquet file (encrypted footer)", null, f.facts, f.warnings) }
        if (footerLen > MAX_FOOTER) { f.warn("Footer larger than ${MAX_FOOTER / 1048576} MiB; metadata not parsed"); return@guard InspectionResult("parquet", "Parquet file", null, f.facts, f.warnings) }
        val fb = rd.read(size - 8 - footerLen, footerLen.toInt())
        if (fb.size.toLong() != footerLen) return@guard null
        val t = Th(fb)
        var version: Long? = null; var rows: Long? = null; var groups: Long? = null; var createdBy: String? = null
        val schema = ArrayList<El>()
        val kvKeys = ArrayList<String>(); var kvCount = 0L
        t.lastId = 0
        while (t.ok) {
            val fh = t.field() ?: break
            when (fh[0]) {
                1 -> version = t.zz()
                2 -> {
                    val lh = t.listHeader() ?: break
                    for (i in 0 until lh[0]) { val e = readElement(t) ?: break; if (schema.size < 5000) schema.add(e) }
                    if (lh[0] > 5000) f.warn("Schema has ${lh[0]} elements; only the first 5000 were kept")
                }
                3 -> rows = t.zz()
                4 -> { val lh = t.listHeader() ?: break; groups = lh[0]; for (i in 0 until lh[0]) { t.skip(12); if (!t.ok) break } }
                5 -> {
                    val lh = t.listHeader() ?: break; kvCount = lh[0]
                    for (i in 0 until lh[0]) {
                        val save = t.lastId; t.lastId = 0
                        var key: String? = null
                        while (t.ok) { val x = t.field() ?: break; if (x[0] == 1 && x[1] == 8) key = t.str() else t.skip(x[1]) }
                        t.lastId = save
                        if (!t.ok) break
                        if (key != null && kvKeys.size < 30) kvKeys.add(GsKit.clip(key, 100))
                    }
                }
                6 -> createdBy = t.str()
                else -> t.skip(fh[1])
            }
        }
        if (!t.ok) f.warn("Footer metadata could not be fully parsed (corrupt or unsupported)")
        f.add("Metadata", "Format version", version)
        f.add("Metadata", "Row count", rows)
        f.add("Metadata", "Row groups", groups)
        f.add("Metadata", "Created by", createdBy?.let { GsKit.clip(it, 200) })
        if (kvCount > 0) { f.add("Metadata", "Key-value entries", kvCount); f.add("Metadata", "Key-value keys", kvKeys.joinToString(", ")) }
        // top-level columns
        if (schema.isNotEmpty()) {
            val root = schema[0]
            var idx = 1; var cols = 0
            fun walk(el: El, depth: Int): Int { // returns count of elements consumed after el
                var consumed = 0
                var n = el.children
                while (n > 0 && idx < schema.size && depth < 32) {
                    val c = schema[idx++]; consumed++
                    consumed += walk(c, depth + 1); n--
                }
                return consumed
            }
            val topNames = ArrayList<String>()
            var remaining = root.children
            while (remaining > 0 && idx < schema.size) {
                val c = schema[idx++]; remaining--
                val nested = walk(c, 1)
                cols++
                if (topNames.size < 200) {
                    val rep = REPS.getOrNull(c.rep)?.let { " $it" } ?: ""
                    topNames.add(c.name + ": " + (if (c.children > 0) "group ($nested nested)" else colType(c)) + rep)
                }
            }
            f.add("Schema", "Schema root", root.name)
            f.add("Schema", "Top-level columns", cols)
            for (n in topNames.take(100)) f.add("Columns", n.substringBefore(": "), n.substringAfter(": "))
        }
        InspectionResult("parquet", "Apache Parquet file",
            listOfNotNull(rows?.let { "$it rows" }, groups?.let { "$it row groups" }).joinToString(", ").ifEmpty { null },
            f.facts, f.warnings)
    }
}
