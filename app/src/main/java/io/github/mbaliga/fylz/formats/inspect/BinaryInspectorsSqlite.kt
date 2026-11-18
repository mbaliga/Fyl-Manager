package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** SQLite 3 database files: header fields and the schema read from sqlite_master. */
object BinarySqliteInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("sqlite")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = binSafe { run(source) }

    private const val MAX_PAGES = 128
    private const val MAX_OBJECTS = 2000

    private class Obj(val type: String, val name: String, val table: String, val root: Long, val sql: String?)

    private fun varint(b: ByteArray, pos: Int): Pair<Long, Int>? {
        var v = 0L
        for (i in 0 until 9) {
            if (pos + i >= b.size) return null
            val x = b[pos + i].toInt() and 0xFF
            if (i == 8) return Pair((v shl 8) or x.toLong(), 9)
            v = (v shl 7) or (x and 0x7F).toLong()
            if (x and 0x80 == 0) return Pair(v, i + 1)
        }
        return null
    }

    private fun serialSize(t: Long): Long = when {
        t in 0..4 -> t
        t == 5L -> 6
        t == 6L || t == 7L -> 8
        t == 8L || t == 9L -> 0
        t >= 12 -> (t - 12) / 2 + 0
        else -> -1
    }

    private fun run(source: ByteSource): InspectionResult? {
        val rd = BinReader(source)
        val h = rd.read(0, 100)
        if (h.size < 16 || !Bytes.startsWith(h, "SQLite format 3") || h[15].toInt() != 0) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        if (h.size < 100) {
            warnings += "Database header is truncated (${h.size} of 100 bytes)"
            return InspectionResult("sqlite", "SQLite 3 database", null, facts, warnings)
        }
        val rawPs = Bytes.u16be(h, 16)
        val pageSize = if (rawPs == 1) 65536 else rawPs
        val validPs = pageSize in 512..65536 && Integer.bitCount(pageSize) == 1
        facts += Fact("Page size", if (validPs) "$pageSize bytes" else "invalid ($rawPs)", "Header")
        val wv = Bytes.u8(h, 18); val rv = Bytes.u8(h, 19)
        val mode = when (wv) { 1 -> "rollback journal"; 2 -> "WAL"; else -> "unknown ($wv)" }
        facts += Fact("Journal mode (file format)", "$mode (write $wv, read $rv)", "Header")
        val reserved = Bytes.u8(h, 20)
        facts += Fact("Reserved bytes per page", reserved.toString(), "Header")
        val counter = Bytes.u32be(h, 24)
        val dbPages = Bytes.u32be(h, 28)
        val validFor = Bytes.u32be(h, 92)
        val computed = if (validPs) rd.size / pageSize else -1L
        facts += Fact("File change counter", counter.toString(), "Header")
        if (dbPages != 0L && validFor == counter) facts += Fact("Page count", dbPages.toString(), "Header")
        else if (computed >= 0) facts += Fact("Page count", "$computed (derived from file size; header value ${if (dbPages == 0L) "unset" else "stale"})", "Header")
        if (validPs && rd.size % pageSize != 0L) warnings += "File size is not a multiple of the page size"
        facts += Fact("Freelist pages", Bytes.u32be(h, 36).toString(), "Header")
        facts += Fact("Schema cookie (schema version)", Bytes.u32be(h, 40).toString(), "Header")
        facts += Fact("Schema format", Bytes.u32be(h, 44).toString(), "Header")
        val enc = Bytes.u32be(h, 56)
        val encName = when (enc) { 1L -> "UTF-8"; 2L -> "UTF-16le"; 3L -> "UTF-16be"; else -> "unknown ($enc)" }
        facts += Fact("Text encoding", encName, "Header")
        facts += Fact("User version", Bytes.u32be(h, 60).toInt().toString(), "Header")
        val appId = Bytes.u32be(h, 68)
        val appName = when (appId) { 0x47504B47L -> " (GeoPackage)"; 0x47503130L -> " (GeoPackage 1.0)"; 0x47503131L -> " (GeoPackage 1.1)"; else -> "" }
        facts += Fact("Application ID", BinFmt.hex(appId) + appName, "Header")
        facts += Fact("Auto-vacuum", if (Bytes.u32be(h, 52) == 0L) "off" else if (Bytes.u32be(h, 64) != 0L) "incremental" else "full", "Header")
        val ver = Bytes.u32be(h, 96)
        if (ver in 3000000..3999999) {
            facts += Fact("Last written by SQLite", "${ver / 1000000}.${(ver / 1000) % 1000}.${ver % 1000}", "Header")
        }

        // ---- schema
        val objs = ArrayList<Obj>()
        var text: String? = null
        var schemaRead = false
        if (validPs && enc in 1L..3L) {
            val usable = pageSize - reserved
            val totalPages = rd.size / pageSize
            val visited = HashSet<Long>()
            val stack = ArrayDeque<Long>()
            stack.addLast(1L)
            var pagesRead = 0
            while (stack.isNotEmpty() && pagesRead < MAX_PAGES && objs.size < MAX_OBJECTS) {
                val pg = stack.removeLast()
                if (pg < 1 || pg > totalPages || !visited.add(pg)) continue
                val page = rd.read((pg - 1) * pageSize, pageSize.toLong())
                pagesRead++
                if (page.size < pageSize) { warnings += "Schema page $pg is truncated"; continue }
                val hb = if (pg == 1L) 100 else 0
                val type = Bytes.u8(page, hb)
                if (pg == 1L && (type == 0x0D || type == 0x05)) schemaRead = true
                val nCells = Bytes.u16be(page, hb + 3)
                when (type) {
                    0x05 -> {
                        val ptrs = hb + 12
                        val kids = ArrayList<Long>()
                        for (i in 0 until minOf(nCells, 2000)) {
                            val co = Bytes.u16be(page, ptrs + i * 2)
                            if (co < ptrs || co + 4 > page.size) continue
                            kids += Bytes.u32be(page, co)
                        }
                        kids += Bytes.u32be(page, hb + 8)
                        for (k in kids.asReversed()) stack.addLast(k)
                    }
                    0x0D -> {
                        val ptrs = hb + 8
                        for (i in 0 until minOf(nCells, 2000)) {
                            if (objs.size >= MAX_OBJECTS) break
                            val co = Bytes.u16be(page, ptrs + i * 2)
                            if (co < ptrs || co >= page.size) continue
                            val cell = parseCell(page, co, usable) ?: continue
                            objs += decode(cell, enc) ?: continue
                        }
                    }
                    else -> if (pg == 1L) warnings += "Page 1 is not a table b-tree page (type 0x${Integer.toHexString(type)}); schema not read"
                }
            }
            if (stack.isNotEmpty() && pagesRead >= MAX_PAGES) warnings += "Schema is large; only the first $MAX_PAGES pages were read"
        } else if (!validPs) warnings += "Invalid page size; schema not read"

        if (objs.isNotEmpty() || schemaRead) {
            val tables = objs.filter { it.type == "table" }
            val user = tables.filter { !it.name.startsWith("sqlite_") }
            facts += Fact("Tables", user.size.toString(), "Schema")
            facts += Fact("Indexes", objs.count { it.type == "index" }.toString(), "Schema")
            facts += Fact("Views", objs.count { it.type == "view" }.toString(), "Schema")
            facts += Fact("Triggers", objs.count { it.type == "trigger" }.toString(), "Schema")
            if (tables.size != user.size) facts += Fact("Internal tables", (tables.size - user.size).toString(), "Schema")
            for (t in user.take(300)) facts += Fact("Table", t.name, "Tables")
            if (user.size > 300) facts += Fact("Table", "... (+${user.size - 300} more)", "Tables")
            for (v in objs.filter { it.type == "view" }.take(100)) facts += Fact("View", v.name, "Views")
            val sb = StringBuilder()
            for (o in objs) {
                val s = o.sql ?: continue
                if (sb.length + s.length + 2 > FormatInspector.MAX_TEXT_CHARS) break
                sb.append(s).append(";\n")
            }
            if (sb.isNotEmpty()) text = sb.toString()
        }
        val tableCount = objs.count { it.type == "table" && !it.name.startsWith("sqlite_") }
        val title = "SQLite 3 database"
        val summary = buildString {
            append(title)
            if (validPs) append(", page size ").append(pageSize)
            if (objs.isNotEmpty()) append(", ").append(tableCount).append(" tables")
        }
        return InspectionResult("sqlite", title, summary, facts, warnings, text)
    }

    /** Returns column payloads (header + values region) of a table-leaf cell, local part only. */
    private class Cell(val payload: ByteArray)

    private fun parseCell(page: ByteArray, off: Int, usable: Int): Cell? {
        val (psz, l1) = varint(page, off) ?: return null
        val (_, l2) = varint(page, off + l1) ?: return null
        val start = off + l1 + l2
        if (psz < 0 || psz > Int.MAX_VALUE) return null
        val x = (usable - 35).toLong()
        val local: Long = if (psz <= x) psz else {
            val m = ((usable - 12L) * 32 / 255) - 23
            val k = m + ((psz - m) % (usable - 4L))
            if (k <= x) k else m
        }
        val end = minOf(start.toLong() + local, page.size.toLong()).toInt()
        if (end <= start) return null
        return Cell(page.copyOfRange(start, end))
    }

    private fun decode(c: Cell, enc: Long): Obj? {
        val b = c.payload
        val (hdrLen, hl) = varint(b, 0) ?: return null
        if (hdrLen > b.size || hdrLen < hl) return null
        var p = hl
        val types = ArrayList<Long>()
        while (p < hdrLen && types.size < 16) {
            val (t, l) = varint(b, p) ?: return null
            types += t; p += l
        }
        if (types.size < 4) return null
        var pos = hdrLen.toInt()
        val vals = arrayOfNulls<Any>(types.size)
        for ((i, t) in types.withIndex()) {
            val sz = serialSize(t)
            if (sz < 0 || pos + sz > b.size) { if (i < 4) return null else break }
            val s = sz.toInt()
            when {
                t == 0L -> vals[i] = null
                t in 1..6 -> {
                    var v = if ((b[pos].toInt()) < 0) -1L else 0L
                    for (k in 0 until s) v = (v shl 8) or (b[pos + k].toLong() and 0xFF)
                    vals[i] = v
                }
                t == 8L -> vals[i] = 0L
                t == 9L -> vals[i] = 1L
                t >= 13 && t % 2 == 1L -> vals[i] = decodeText(b, pos, s, enc)
                else -> vals[i] = null
            }
            pos += s
        }
        val type = vals[0] as? String ?: return null
        val name = vals[1] as? String ?: return null
        val tbl = vals[2] as? String ?: name
        val root = vals[3] as? Long ?: 0L
        return Obj(type, name, tbl, root, if (vals.size > 4) vals[4] as? String else null)
    }

    private fun decodeText(b: ByteArray, pos: Int, len: Int, enc: Long): String {
        val cs = when (enc) { 2L -> Charsets.UTF_16LE; 3L -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        val n = minOf(len, 65536)
        return String(b, pos, n, cs)
    }
}
