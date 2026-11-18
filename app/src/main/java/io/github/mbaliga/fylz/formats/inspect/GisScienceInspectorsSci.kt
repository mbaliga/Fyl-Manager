package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** FITS: primary header cards plus a bounded walk of extension HDUs. */
object GisScienceFitsInspector : FormatInspector {
    override val formatIds = setOf("fits")
    private const val BLOCK = 2880

    private class Hdu(val cards: LinkedHashMap<String, String>, val headerBlocks: Int, val complete: Boolean)

    private fun parseValue(card: String): String {
        var v = card.substring(10).trimStart()
        if (v.startsWith("'")) {
            val sb = StringBuilder(); var i = 1
            while (i < v.length) {
                val c = v[i]
                if (c == '\'') { if (i + 1 < v.length && v[i + 1] == '\'') { sb.append('\''); i += 2; continue } else break }
                sb.append(c); i++
            }
            return sb.toString().trimEnd()
        }
        val s = v.indexOf('/')
        if (s >= 0) v = v.substring(0, s)
        return v.trim()
    }

    private fun readHeader(rd: GsReader, offset: Long): Hdu? {
        val cards = LinkedHashMap<String, String>()
        var blocks = 0
        while (blocks < 64) {
            val b = rd.read(offset + blocks.toLong() * BLOCK, BLOCK)
            if (b.size < BLOCK) return if (cards.isEmpty()) null else Hdu(cards, blocks, false)
            blocks++
            for (i in 0 until 36) {
                val card = String(b, i * 80, 80, Charsets.ISO_8859_1)
                val key = card.substring(0, 8).trim()
                if (key == "END") return Hdu(cards, blocks, true)
                if (card.length >= 10 && card[8] == '=' && card[9] == ' ' && key.isNotEmpty() && !cards.containsKey(key)) {
                    cards[key] = parseValue(card)
                }
            }
        }
        return Hdu(cards, blocks, false)
    }

    private fun dims(c: Map<String, String>): List<Long>? {
        val n = c["NAXIS"]?.toIntOrNull() ?: return null
        if (n < 0 || n > 999) return null
        val out = ArrayList<Long>()
        for (i in 1..n) out.add(c["NAXIS$i"]?.toLongOrNull() ?: return null)
        return out
    }

    private fun dataBytes(c: Map<String, String>, primary: Boolean): Long? {
        val bitpix = c["BITPIX"]?.toIntOrNull() ?: return null
        if (bitpix !in setOf(8, 16, 32, 64, -32, -64)) return null
        val d = dims(c) ?: return null
        val groups = primary && c["GROUPS"] == "T"
        var prod = 1L
        try {
            for ((i, x) in d.withIndex()) {
                if (x < 0) return null
                if (groups && i == 0) continue
                prod = Math.multiplyExact(prod, x)
            }
            if (d.isEmpty()) return 0L
            val pcount = c["PCOUNT"]?.toLongOrNull() ?: 0L
            val gcount = c["GCOUNT"]?.toLongOrNull() ?: 1L
            if (pcount < 0 || gcount < 0) return null
            return Math.multiplyExact(Math.multiplyExact((Math.abs(bitpix) / 8).toLong(), gcount), Math.addExact(pcount, prod))
        } catch (e: ArithmeticException) { return null }
    }

    private fun dimStr(d: List<Long>) = if (d.isEmpty()) "no data" else d.joinToString(" x ")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val first = rd.read(0, 10)
        if (first.size < 10 || !Bytes.startsWith(first, "SIMPLE  = ")) return@guard null
        val hdu = readHeader(rd, 0) ?: return@guard null
        val c = hdu.cards
        val f = GsFacts()
        if (!hdu.complete) f.warn("Primary header has no END card within the examined range")
        f.add("Primary header", "SIMPLE", c["SIMPLE"])
        val bitpix = c["BITPIX"]?.toIntOrNull()
        val bpName = when (bitpix) { 8 -> "8-bit unsigned integer"; 16 -> "16-bit signed integer"; 32 -> "32-bit signed integer"
            64 -> "64-bit signed integer"; -32 -> "32-bit float"; -64 -> "64-bit float"; else -> null }
        f.add("Primary header", "BITPIX", if (bpName != null) "$bitpix ($bpName)" else c["BITPIX"])
        f.add("Primary header", "NAXIS", c["NAXIS"])
        val d = dims(c)
        if (d != null) f.add("Primary header", "Dimensions", dimStr(d))
        else f.warn("NAXIS/NAXISn missing or invalid")
        for ((k, label) in listOf("OBJECT" to "Object", "TELESCOP" to "Telescope", "INSTRUME" to "Instrument", "OBSERVER" to "Observer",
            "DATE-OBS" to "Observation date", "EXPTIME" to "Exposure time", "FILTER" to "Filter", "BUNIT" to "Pixel unit",
            "ORIGIN" to "Origin", "DATE" to "File date", "EXTEND" to "EXTEND", "BSCALE" to "BSCALE", "BZERO" to "BZERO",
            "CTYPE1" to "CTYPE1", "CTYPE2" to "CTYPE2"))
            f.add("Primary header", label, c[k]?.let { GsKit.clip(it, 200) })
        f.add("Primary header", "Header cards parsed (keyword = value)", c.size)
        // walk HDUs
        var count = 1
        var off = 0L
        var cur: Hdu = hdu
        var primary = true
        val extSummaries = ArrayList<String>()
        var truncatedWalk = false
        var iter = 0
        while (iter < 2000) {
            iter++
            val db = dataBytes(cur.cards, primary)
            if (db == null || !cur.complete) { f.warn("HDU ${count - 1}: cannot determine data size; extension walk stopped"); truncatedWalk = true; break }
            val padded = try { Math.multiplyExact((db + BLOCK - 1) / BLOCK, BLOCK.toLong()) } catch (e: ArithmeticException) { f.warn("Data size overflow"); break }
            val next = off + cur.headerBlocks.toLong() * BLOCK + padded
            if (next < 0 || next >= source.size) {
                if (next > source.size) f.warn("Declared data of HDU ${count - 1} extends past the end of the file (truncated?)")
                break
            }
            val nh = readHeader(rd, next)
            if (nh == null) { f.warn("Trailing bytes after HDU ${count - 1} do not form a header"); break }
            if (!nh.cards.containsKey("XTENSION")) { f.warn("Block after HDU ${count - 1} is not an XTENSION header"); break }
            primary = false; cur = nh; off = next; count++
            if (extSummaries.size < 30) {
                val x = nh.cards["XTENSION"] ?: "?"
                val nm = nh.cards["EXTNAME"]
                val dd = dims(nh.cards)
                val rows = if (x.trim() == "BINTABLE" || x.trim() == "TABLE") " rows=" + nh.cards["NAXIS2"] + " fields=" + nh.cards["TFIELDS"] else ""
                extSummaries.add("#${count - 1} ${x.trim()}" + (if (nm != null) " '$nm'" else "") + (if (dd != null && rows.isEmpty()) " [${dimStr(dd)}]" else "") + rows)
            }
        }
        f.add("Extensions", "Extension count" + if (truncatedWalk) " (at least)" else "", count - 1)
        for (s in extSummaries) f.add("Extensions", "Extension", s)
        InspectionResult("fits", "FITS astronomical data",
            (c["OBJECT"]?.let { "Object $it, " } ?: "") + (d?.let { dimStr(it) } ?: "unknown size") + ", ${count - 1} extension(s)",
            f.facts, f.warnings)
    }
}

/** HDF5 superblock only (no tree walk). */
object GisScienceHdf5Inspector : FormatInspector {
    override val formatIds = setOf("h5")
    private val SIG = byteArrayOf(0x89.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    private fun uN(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until minOf(n, 8)) v = v or (Bytes.u8(b, o + i).toLong() shl (8 * i))
        return v
    }

    private fun addr(v: Long, n: Int): String =
        if (n < 8 && v == (1L shl (8 * n)) - 1 || v == -1L) "undefined" else "0x" + java.lang.Long.toHexString(v)

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        var base = -1L
        var pos = 0L
        var tries = 0
        while (tries < 20) {
            val h = rd.read(pos, 8)
            if (h.size == 8 && h.contentEquals(SIG)) { base = pos; break }
            pos = if (pos == 0L) 512 else pos * 2
            if (pos >= source.size) break
            tries++
        }
        if (base < 0) return@guard null
        val b = rd.read(base, 128)
        if (b.size < 16) return@guard null
        val f = GsFacts()
        val ver = Bytes.u8(b, 8)
        f.add("Superblock", "Superblock version", ver)
        if (base > 0) f.add("Superblock", "User block size", "$base bytes")
        var offSize = 0; var lenSize = 0
        var rootAddr: Long? = null; var eof: Long? = null; var baseAddr: Long? = null
        when (ver) {
            0, 1 -> {
                f.add("Superblock", "Free-space storage version", Bytes.u8(b, 9))
                f.add("Superblock", "Root group symbol table entry version", Bytes.u8(b, 10))
                f.add("Superblock", "Shared header message version", Bytes.u8(b, 12))
                offSize = Bytes.u8(b, 13); lenSize = Bytes.u8(b, 14)
                f.add("Superblock", "Group leaf node K", Bytes.u16le(b, 16))
                f.add("Superblock", "Group internal node K", Bytes.u16le(b, 18))
                var p = 24
                if (ver == 1) { f.add("Superblock", "Indexed storage internal node K", Bytes.u16le(b, 24)); p = 28 }
                if (offSize in setOf(2, 4, 8)) {
                    baseAddr = uN(b, p, offSize)
                    eof = uN(b, p + 2 * offSize, offSize)
                    // root group symbol table entry: link name offset, object header address
                    rootAddr = uN(b, p + 4 * offSize + offSize, offSize)
                }
            }
            2, 3 -> {
                offSize = Bytes.u8(b, 9); lenSize = Bytes.u8(b, 10)
                val flags = Bytes.u8(b, 11)
                f.add("Superblock", "File consistency flags", "0x" + Integer.toHexString(flags))
                if (offSize in setOf(2, 4, 8)) {
                    baseAddr = uN(b, 12, offSize)
                    eof = uN(b, 12 + 2 * offSize, offSize)
                    rootAddr = uN(b, 12 + 3 * offSize, offSize)
                }
            }
            else -> f.warn("Unknown superblock version $ver")
        }
        if (ver in 0..3) {
            f.add("Superblock", "Size of offsets", "$offSize bytes")
            f.add("Superblock", "Size of lengths", "$lenSize bytes")
            if (offSize !in setOf(2, 4, 8)) f.warn("Unusual size of offsets $offSize; addresses not decoded")
            if (lenSize !in setOf(2, 4, 8)) f.warn("Unusual size of lengths $lenSize")
            baseAddr?.let { f.add("Superblock", "Base address", addr(it, offSize)) }
            eof?.let {
                f.add("Superblock", "End-of-file address", addr(it, offSize))
                if (it > 0 && base + it > source.size && it != -1L) f.warn("End-of-file address is beyond the actual file size (file truncated?)")
            }
            rootAddr?.let { f.add("Superblock", "Root group address", addr(it, offSize)) }
        }
        f.add("Note", "Tree walk", "Groups and datasets are not enumerated; netCDF-4 files are also HDF5 and look the same at this level")
        InspectionResult("h5", "HDF5 file", "HDF5 superblock version $ver", f.facts, f.warnings)
    }
}

/** NetCDF classic (CDF-1, CDF-2, CDF-5). */
object GisScienceNetCdfInspector : FormatInspector {
    override val formatIds = setOf("nc")

    private class Cur(val b: ByteArray, var p: Int, val wide: Boolean) {
        var ok = true
        fun i32(): Long { if (p + 4 > b.size) { ok = false; return 0 }; val v = Bytes.u32be(b, p); p += 4; return v }
        fun i64(): Long { if (p + 8 > b.size) { ok = false; return 0 }; val v = Bytes.u64be(b, p); p += 8; return v }
        fun nn(): Long = if (wide) i64() else i32()
        fun skipPad(n: Long) { val pad = (4 - (n % 4)) % 4; adv(n + pad) }
        fun adv(n: Long) { if (n < 0 || p + n > b.size) { ok = false; p = b.size } else p += n.toInt() }
        fun name(): String {
            val n = nn()
            if (!ok || n < 0 || n > 4096 || p + n > b.size) { ok = false; return "" }
            val s = String(b, p, n.toInt(), Charsets.UTF_8); skipPad(n); return s
        }
    }

    private fun typeName(t: Long) = when (t.toInt()) {
        1 -> "byte"; 2 -> "char"; 3 -> "short"; 4 -> "int"; 5 -> "float"; 6 -> "double"
        7 -> "ubyte"; 8 -> "ushort"; 9 -> "uint"; 10 -> "int64"; 11 -> "uint64"; else -> "type$t"
    }

    private fun typeSize(t: Long): Int = when (t.toInt()) { 1, 2, 7 -> 1; 3, 8 -> 2; 4, 5, 9 -> 4; 6, 10, 11 -> 8; else -> 0 }

    private fun readAtts(c: Cur, f: GsFacts, group: String, version: Int, prefix: String) {
        val tag = c.i32(); val n = c.nn()
        if (!c.ok) return
        if (tag == 0L) return
        if (tag != 12L) { f.warn("Bad attribute list tag $tag"); c.ok = false; return }
        if (n > 4096) f.warn("More than 4096 attributes; only the first are listed")
        var shown = 0
        for (i in 0 until minOf(n, 4096L).toInt()) {
            val name = c.name(); val t = c.i32(); val cnt = c.nn()
            if (!c.ok) return
            val sz = typeSize(t)
            if (sz == 0) { f.warn("Unknown attribute type $t"); c.ok = false; return }
            if (cnt < 0 || cnt > Int.MAX_VALUE / 8) { c.ok = false; return }
            val bytes = cnt * sz
            val start = c.p
            if (start + bytes > c.b.size) { c.ok = false; return }
            var v: String
            if (t == 2L) {
                v = GsKit.clip(String(c.b, start, bytes.toInt(), Charsets.UTF_8).trimEnd('\u0000'), 300)
            } else {
                val bb = ByteBuffer.wrap(c.b).order(ByteOrder.BIG_ENDIAN)
                val vals = ArrayList<String>()
                for (k in 0 until minOf(cnt, 8L).toInt()) {
                    val o = start + k * sz
                    vals.add(when (t.toInt()) {
                        1 -> bb.get(o).toString(); 7 -> (bb.get(o).toInt() and 0xFF).toString()
                        3 -> bb.getShort(o).toString(); 8 -> (bb.getShort(o).toInt() and 0xFFFF).toString()
                        4 -> bb.getInt(o).toString(); 9 -> (bb.getInt(o).toLong() and 0xFFFFFFFFL).toString()
                        5 -> GsKit.num(bb.getFloat(o).toDouble()); 6 -> GsKit.num(bb.getDouble(o))
                        10 -> bb.getLong(o).toString(); else -> java.lang.Long.toUnsignedString(bb.getLong(o))
                    })
                }
                v = vals.joinToString(", ") + (if (cnt > 8) ", ..." else "")
            }
            c.skipPad(bytes)
            if (shown < 100) { f.add(group, prefix + name, v + if (t != 2L) " (${typeName(t)})" else ""); shown++ }
        }
    }

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val b = rd.head(1 shl 20)
        if (b.size < 8 || !Bytes.startsWith(b, "CDF")) return@guard null
        val version = Bytes.u8(b, 3)
        if (version !in setOf(1, 2, 5)) return@guard null
        val f = GsFacts()
        val c = Cur(b, 4, version == 5)
        f.add("Header", "Format", when (version) { 1 -> "CDF-1 (classic)"; 2 -> "CDF-2 (64-bit offset)"; else -> "CDF-5 (64-bit data)" })
        val numrecs = c.nn()
        f.add("Header", "Record count", if ((version != 5 && numrecs == 0xFFFFFFFFL) || numrecs == -1L) "streaming (unknown)" else numrecs.toString())
        // dimensions
        val dimNames = ArrayList<String>(); val dimLens = ArrayList<Long>()
        val dtag = c.i32(); val dn = c.nn()
        if (c.ok && dtag == 10L) {
            if (dn > 4096) f.warn("More than 4096 dimensions; only the first are listed")
            for (i in 0 until minOf(dn, 4096L).toInt()) {
                val nm = c.name(); val len = c.nn()
                if (!c.ok) break
                dimNames.add(nm); dimLens.add(len)
                f.add("Dimensions", nm, if (len == 0L) "unlimited (record)" else len.toString())
            }
        } else if (c.ok && dtag != 0L) { f.warn("Bad dimension list tag $dtag"); c.ok = false }
        if (c.ok) readAtts(c, f, "Global attributes", version, "")
        var nvars = 0L
        if (c.ok) {
            val vtag = c.i32(); val vn = c.nn()
            if (c.ok && vtag == 11L) {
                nvars = vn
                if (vn > 4096) f.warn("More than 4096 variables; only the first are listed")
                for (i in 0 until minOf(vn, 4096L).toInt()) {
                    val nm = c.name(); val nd = c.nn()
                    if (!c.ok || nd < 0 || nd > 1024) { c.ok = false; break }
                    val ds = ArrayList<String>()
                    for (k in 0 until nd.toInt()) { val id = c.nn(); ds.add(if (id in 0 until dimNames.size.toLong()) dimNames[id.toInt()] else "dim#$id") }
                    // variable attributes
                    val sub = GsFacts()
                    readAtts(c, sub, "Variable attributes", version, "$nm:")
                    val t = c.i32(); c.nn(); if (version == 1) c.i32() else c.i64()
                    if (!c.ok) break
                    if (i < 200) f.add("Variables", nm, typeName(t) + " [" + ds.joinToString(", ") + "]")
                    if (i < 30) for (x in sub.facts.take(10)) f.facts.add(x)
                }
            } else if (c.ok && vtag != 0L) f.warn("Bad variable list tag $vtag")
        }
        if (!c.ok) f.warn("Header could not be fully parsed (truncated or corrupt)")
        InspectionResult("nc", "NetCDF classic", "${dimNames.size} dimensions, $nvars variables", f.facts, f.warnings)
    }
}

/** NIfTI-1 and NIfTI-2 (uncompressed single-file .nii or header .hdr). */
object GisScienceNiftiInspector : FormatInspector {
    override val formatIds = setOf("nii")

    private val DT = mapOf(0 to "unknown", 1 to "binary", 2 to "uint8", 4 to "int16", 8 to "int32", 16 to "float32", 32 to "complex64",
        64 to "float64", 128 to "RGB24", 256 to "int8", 512 to "uint16", 768 to "uint32", 1024 to "int64", 1280 to "uint64",
        1536 to "float128", 1792 to "complex128", 2048 to "complex256", 2304 to "RGBA32")
    private val CODE = mapOf(0 to "unknown", 1 to "scanner anatomical", 2 to "aligned anatomical", 3 to "Talairach", 4 to "MNI 152")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val b = rd.read(0, 544)
        if (b.size < 348) return@guard null
        var order = ByteOrder.LITTLE_ENDIAN
        var bb = ByteBuffer.wrap(b).order(order)
        var hdr = bb.getInt(0)
        if (hdr != 348 && hdr != 540) {
            order = ByteOrder.BIG_ENDIAN; bb = ByteBuffer.wrap(b).order(order); hdr = bb.getInt(0)
        }
        val f = GsFacts()
        if (hdr == 348) {
            val magic = Bytes.ascii(b, 344, 3)
            if (magic != "n+1" && magic != "ni1") return@guard null
            val ndim = bb.getShort(40).toInt()
            if (ndim < 0 || ndim > 7) f.warn("dim[0] = $ndim is outside 0..7 (invalid)")
            val dim = (1..minOf(maxOf(ndim, 0), 7)).map { bb.getShort(40 + 2 * it).toInt() }
            val dt = bb.getShort(70).toInt() and 0xFFFF
            f.add("Header", "NIfTI version", if (magic == "n+1") "NIfTI-1 (single file)" else "NIfTI-1 (header/image pair)")
            f.add("Header", "Byte order", if (order == ByteOrder.LITTLE_ENDIAN) "little-endian" else "big-endian")
            f.add("Image", "Dimensions", dim.joinToString(" x "))
            f.add("Image", "Datatype", DT[dt]?.let { "$it ($dt)" } ?: "unknown code $dt")
            f.add("Image", "Bits per voxel", bb.getShort(72).toInt())
            val pix = (1..minOf(maxOf(ndim, 0), 7)).map { GsKit.num(bb.getFloat(76 + 4 * it).toDouble()) }
            f.add("Image", "Voxel size (pixdim)", pix.joinToString(" x "))
            f.add("Image", "Voxel data offset", GsKit.num(bb.getFloat(108).toDouble()))
            f.add("Image", "Scaling slope / intercept", GsKit.num(bb.getFloat(112).toDouble()) + " / " + GsKit.num(bb.getFloat(116).toDouble()))
            val u = b[123].toInt() and 0xFF
            f.add("Units", "Spatial unit", when (u and 7) { 0 -> "unknown"; 1 -> "metre"; 2 -> "millimetre"; 3 -> "micrometre"; else -> "code ${u and 7}" })
            f.add("Units", "Temporal unit", when (u and 0x38) { 0 -> "unknown"; 8 -> "second"; 16 -> "millisecond"; 24 -> "microsecond"
                32 -> "Hz"; 40 -> "ppm"; 48 -> "rad/s"; else -> "code ${u and 0x38}" })
            val q = bb.getShort(252).toInt(); val s = bb.getShort(254).toInt()
            f.add("Orientation", "qform code", "$q (${CODE[q] ?: "unrecognised"})")
            f.add("Orientation", "sform code", "$s (${CODE[s] ?: "unrecognised"})")
            f.add("Header", "Description", GsKit.cstr(b, 148, 80))
            f.add("Header", "Auxiliary file", GsKit.cstr(b, 228, 24))
            f.add("Header", "Intent code", bb.getShort(68).toInt())
            InspectionResult("nii", "NIfTI-1 neuroimaging volume", "${dim.joinToString("x")} ${DT[dt] ?: "?"}", f.facts, f.warnings)
        } else if (hdr == 540) {
            if (b.size < 540 || !Bytes.startsWith(b, "n+2", 4) && !Bytes.startsWith(b, "ni2", 4)) return@guard null
            val ndim = bb.getLong(16).toInt()
            if (ndim < 0 || ndim > 7) f.warn("dim[0] = $ndim is outside 0..7 (invalid)")
            val dim = (1..minOf(maxOf(ndim, 0), 7)).map { bb.getLong(16 + 8 * it) }
            val dt = bb.getShort(12).toInt() and 0xFFFF
            f.add("Header", "NIfTI version", "NIfTI-2")
            f.add("Header", "Byte order", if (order == ByteOrder.LITTLE_ENDIAN) "little-endian" else "big-endian")
            f.add("Image", "Dimensions", dim.joinToString(" x "))
            f.add("Image", "Datatype", DT[dt]?.let { "$it ($dt)" } ?: "unknown code $dt")
            f.add("Image", "Bits per voxel", bb.getShort(14).toInt())
            f.add("Image", "Voxel size (pixdim)", (1..minOf(maxOf(ndim, 0), 7)).joinToString(" x ") { GsKit.num(bb.getDouble(104 + 8 * it)) })
            f.add("Image", "Voxel data offset", bb.getLong(168))
            f.add("Image", "Scaling slope / intercept", GsKit.num(bb.getDouble(176)) + " / " + GsKit.num(bb.getDouble(184)))
            val u = bb.getInt(500)
            f.add("Units", "Spatial unit", when (u and 7) { 0 -> "unknown"; 1 -> "metre"; 2 -> "millimetre"; 3 -> "micrometre"; else -> "code ${u and 7}" })
            f.add("Units", "Temporal unit", when (u and 0x38) { 0 -> "unknown"; 8 -> "second"; 16 -> "millisecond"; 24 -> "microsecond"
                32 -> "Hz"; 40 -> "ppm"; 48 -> "rad/s"; else -> "code ${u and 0x38}" })
            val q = bb.getInt(344); val s = bb.getInt(348)
            f.add("Orientation", "qform code", "$q (${CODE[q] ?: "unrecognised"})")
            f.add("Orientation", "sform code", "$s (${CODE[s] ?: "unrecognised"})")
            f.add("Header", "Description", GsKit.cstr(b, 240, 80))
            f.add("Header", "Auxiliary file", GsKit.cstr(b, 320, 24))
            InspectionResult("nii", "NIfTI-2 neuroimaging volume", "${dim.joinToString("x")} ${DT[dt] ?: "?"}", f.facts, f.warnings)
        } else null
    }
}
