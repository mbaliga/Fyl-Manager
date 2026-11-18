package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

private fun cadF32le(b: ByteArray, o: Int): Double = Float.fromBits(Bytes.u32le(b, o).toInt()).toDouble()

/** STL, ASCII and binary. */
object CadStlInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("stl")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        run(source)
    } catch (t: Throwable) { null }

    private fun run(source: ByteSource): InspectionResult? {
        val size = source.size
        if (size < 15) return null
        val head = CadIo.readUpTo(source, 0, 84)
        val declared = if (head.size >= 84) Bytes.u32le(head, 80) else -1L
        val binaryMatches = declared >= 0 && size == 84L + 50L * declared
        val startsSolid = CadIo.latin1(head, 0, 5).equals("solid", ignoreCase = false)
        return if (binaryMatches || plausibleBinary(source, head, declared, startsSolid)) binary(source, head, declared, binaryMatches, startsSolid)
        else if (startsSolid) ascii(source) else null
    }

    /** Count/size mismatch is accepted only when the header is plain text or the first triangles hold sane floats. */
    private fun plausibleBinary(source: ByteSource, head: ByteArray, declared: Long, startsSolid: Boolean): Boolean {
        if (startsSolid || head.size < 84 || declared <= 0) return false
        var printable = true
        for (i in 0 until 80) { val c = Bytes.u8(head, i); if (!(c == 0 || c in 0x20..0x7E)) { printable = false; break } }
        if (printable) return true
        val avail = (source.size - 84) / 50
        if (avail < 1) return false
        val n = minOf(8L, avail).toInt()
        val buf = source.read(84, n * 50)
        if (buf.size < n * 50) return false
        for (t in 0 until n) for (k in 0 until 12) {
            val v = cadF32le(buf, t * 50 + k * 4)
            if (!v.isFinite() || Math.abs(v) > 1e9) return false
        }
        return true
    }

    private fun binary(source: ByteSource, head: ByteArray, declared: Long, exact: Boolean, startsSolid: Boolean): InspectionResult {
        val size = source.size
        val f = CadFacts()
        f.add("Format", "Encoding", "binary")
        var name = CadIo.latin1(head, 0, 80).filter { it.code in 0x20..0x7E }.trim()
        f.add("Header", "Header text", name)
        val avail = maxOf(0L, (size - 84) / 50)
        f.add("Mesh", "Triangles (declared)", CadIo.count(declared))
        if (exact) f.add("Mesh", "Size check", "file size matches 84 + 50 x triangles")
        else {
            f.warn("Declared triangle count $declared does not match file size $size (room for $avail triangles)")
            f.add("Mesh", "Triangles (fit in file)", CadIo.count(avail))
        }
        if (startsSolid) f.warn("Header begins with 'solid' although the file is binary STL (common with some exporters)")
        val toScan = minOf(declared, avail)
        val b = CadBounds()
        var scanned = 0L
        var off = 84L
        val chunkTris = 4096
        val budget = FormatInspector.MAX_READ_BYTES - 84
        var used = 0L
        while (scanned < toScan && budget - used >= 50) {
            val nT = minOf(chunkTris.toLong(), toScan - scanned, (budget - used) / 50).toInt()
            val buf = source.read(off, nT * 50)
            if (buf.size < 50) break
            val got = buf.size / 50
            for (t in 0 until got) {
                val o = t * 50 + 12
                for (v in 0 until 3) b.add(cadF32le(buf, o + v * 12), cadF32le(buf, o + v * 12 + 4), cadF32le(buf, o + v * 12 + 8))
            }
            scanned += got; off += got * 50L; used += buf.size
        }
        b.addFacts(f, "Geometry")
        if (scanned < toScan) f.warn("Bounding box covers the first ${CadIo.count(scanned)} triangles only")
        if (b.bad > 0) f.warn("${b.bad} vertices were non-finite and were ignored")
        return InspectionResult("stl", name.ifBlank { "Binary STL" }, "Binary STL, ${CadIo.count(declared)} triangles", f.list, f.warnings)
    }

    private fun ascii(source: ByteSource): InspectionResult? {
        val bytes = CadIo.readUpTo(source, 0, FormatInspector.MAX_READ_BYTES - 84)
        val text = CadIo.latin1(bytes)
        val f = CadFacts()
        val names = ArrayList<String>()
        var facets = 0L
        var solids = 0
        var end = 0
        val b = CadBounds()
        CadIo.forEachLine(text) { raw ->
            val line = raw.trim()
            if (line.startsWith("solid") && (line.length == 5 || line[5] == ' ' || line[5] == '\t')) {
                solids++
                val n = line.substring(5).trim()
                if (names.size < 20 && n.isNotEmpty()) names.add(n)
            } else if (line.startsWith("facet")) facets++
            else if (line.startsWith("vertex")) {
                val p = line.substring(6).trim().split(Regex("\\s+"))
                if (p.size >= 3) {
                    val x = p[0].toDoubleOrNull(); val y = p[1].toDoubleOrNull(); val z = p[2].toDoubleOrNull()
                    if (x != null && y != null && z != null) b.add(x, y, z)
                }
            } else if (line.startsWith("endsolid")) end++
        }
        if (solids == 0 || (facets == 0L && end == 0)) return null
        f.add("Format", "Encoding", "ASCII")
        f.add("Header", "Solid name", names.firstOrNull())
        f.add("Mesh", "Solids", solids.toString())
        f.add("Mesh", "Triangles (facets)", CadIo.count(facets))
        f.add("Mesh", "Vertices", CadIo.count(b.n + b.bad))
        b.addFacts(f, "Geometry")
        if (bytes.size < source.size) f.warn("Only the first 8 MiB were scanned; counts and bounding box are lower bounds")
        else if (end < solids) f.warn("Missing 'endsolid' (file may be truncated)")
        if (b.n + b.bad != facets * 3 && bytes.size >= source.size) f.warn("Vertex count ${b.n + b.bad} is not 3 x facets ($facets)")
        return InspectionResult("stl", names.firstOrNull() ?: "ASCII STL", "ASCII STL, ${CadIo.count(facets)} facets", f.list, f.warnings)
    }
}

/** Wavefront OBJ. */
object CadObjInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("obj")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        run(source)
    } catch (t: Throwable) { null }

    private fun run(source: ByteSource): InspectionResult? {
        val bytes = CadIo.readUpTo(source, 0, FormatInspector.MAX_READ_BYTES)
        if (bytes.isEmpty()) return null
        // reject binary content
        for (i in 0 until minOf(bytes.size, 4096)) if (bytes[i].toInt() == 0) return null
        val text = CadIo.utf8(bytes, CadIo.skipBom(bytes))
        var v = 0L; var vn = 0L; var vt = 0L; var faces = 0L; var tris = 0L; var lines = 0L; var points = 0L
        var vcolour = 0L; var groups = 0; var objects = 0
        val groupNames = LinkedHashSet<String>(); val objNames = LinkedHashSet<String>()
        val mtllibs = LinkedHashSet<String>(); val usemtl = LinkedHashSet<String>()
        val b = CadBounds()
        var cont = false
        CadIo.forEachLine(text) { raw ->
            val wasCont = cont
            val line = raw.trim()
            cont = line.endsWith("\\")
            if (wasCont || line.isEmpty() || line[0] == '#') return@forEachLine
            val sp = line.indexOf(' ').let { if (it < 0) line.length else it }
            val key = line.substring(0, sp)
            val rest = if (sp < line.length) line.substring(sp + 1).trim() else ""
            when (key) {
                "v" -> {
                    v++
                    val p = rest.split(Regex("\\s+"))
                    if (p.size >= 3) {
                        val x = p[0].toDoubleOrNull(); val y = p[1].toDoubleOrNull(); val z = p[2].toDoubleOrNull()
                        if (x != null && y != null && z != null) b.add(x, y, z)
                        if (p.size >= 6) vcolour++
                    }
                }
                "vn" -> vn++
                "vt" -> vt++
                "f" -> { faces++; val n = rest.split(Regex("\\s+")).count { it.isNotEmpty() }; if (n >= 3) tris += n - 2 }
                "l" -> lines++
                "p" -> points++
                "g" -> { groups++; if (groupNames.size < 30 && rest.isNotEmpty()) groupNames.add(rest) }
                "o" -> { objects++; if (objNames.size < 30 && rest.isNotEmpty()) objNames.add(rest) }
                "mtllib" -> if (mtllibs.size < 20) mtllibs.add(rest)
                "usemtl" -> if (usemtl.size < 50) usemtl.add(rest)
            }
        }
        if (v == 0L && faces == 0L && vn == 0L && vt == 0L) return null
        val f = CadFacts()
        f.add("Mesh", "Vertices", CadIo.count(v))
        f.add("Mesh", "Texture coordinates", CadIo.count(vt))
        f.add("Mesh", "Normals", CadIo.count(vn))
        f.add("Mesh", "Faces", CadIo.count(faces))
        f.add("Mesh", "Triangles after fan triangulation", CadIo.count(tris))
        if (lines > 0) f.add("Mesh", "Line elements", CadIo.count(lines))
        if (points > 0) f.add("Mesh", "Point elements", CadIo.count(points))
        if (vcolour > 0) f.add("Mesh", "Vertices with colour", CadIo.count(vcolour))
        f.add("Structure", "Objects (o)", if (objects > 0) "$objects: " + objNames.joinToString(", ") else null)
        f.add("Structure", "Groups (g)", if (groups > 0) "$groups: " + groupNames.joinToString(", ") else null)
        f.add("Materials", "Material libraries", mtllibs.joinToString(", "))
        f.add("Materials", "Materials used", usemtl.joinToString(", "))
        b.addFacts(f, "Geometry")
        if (b.bad > 0) f.warn("${b.bad} vertices were non-finite and were ignored")
        if (bytes.size < source.size) f.warn("Only the first 8 MiB were scanned; counts and bounding box are lower bounds")
        return InspectionResult("obj", objNames.firstOrNull() ?: "Wavefront OBJ", "OBJ, ${CadIo.count(v)} vertices, ${CadIo.count(faces)} faces", f.list, f.warnings)
    }
}

/** Stanford PLY, ASCII and binary. */
object CadPlyInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("ply")

    private class Prop(val name: String, val type: String, val listCount: String?)
    private class Elem(val name: String, val count: Long) { val props = ArrayList<Prop>() }

    private fun typeSize(t: String): Int = when (t) {
        "char", "int8", "uchar", "uint8" -> 1
        "short", "int16", "ushort", "uint16" -> 2
        "int", "int32", "uint", "uint32", "float", "float32" -> 4
        "double", "float64" -> 8
        else -> 0
    }

    private fun readNum(b: ByteArray, o: Int, t: String, le: Boolean): Double {
        fun u(n: Int): Long {
            var r = 0L
            for (k in 0 until n) { val by = Bytes.u8(b, if (le) o + n - 1 - k else o + k).toLong(); r = (r shl 8) or by }
            return r
        }
        return when (t) {
            "char", "int8" -> Bytes.u8(b, o).toByte().toDouble()
            "uchar", "uint8" -> Bytes.u8(b, o).toDouble()
            "short", "int16" -> u(2).toShort().toDouble()
            "ushort", "uint16" -> u(2).toDouble()
            "int", "int32" -> u(4).toInt().toDouble()
            "uint", "uint32" -> u(4).toDouble()
            "float", "float32" -> Float.fromBits(u(4).toInt()).toDouble()
            "double", "float64" -> java.lang.Double.longBitsToDouble(u(8))
            else -> Double.NaN
        }
    }

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        run(source)
    } catch (t: Throwable) { null }

    private fun run(source: ByteSource): InspectionResult? {
        val bytes = CadIo.readUpTo(source, 0, FormatInspector.MAX_READ_BYTES)
        if (!Bytes.startsWith(bytes, "ply")) return null
        val scanLen = minOf(bytes.size, 1 shl 20)
        val headText = CadIo.latin1(bytes, 0, scanLen)
        val endIdx = headText.indexOf("end_header")
        if (endIdx < 0) return null
        var bodyStart = endIdx + "end_header".length
        if (bodyStart < bytes.size && bytes[bodyStart].toInt() == 13) bodyStart++
        if (bodyStart < bytes.size && bytes[bodyStart].toInt() == 10) bodyStart++
        val header = headText.substring(0, endIdx)
        var format: String? = null
        var version: String? = null
        val comments = ArrayList<String>()
        val objInfo = ArrayList<String>()
        val elems = ArrayList<Elem>()
        var first = true
        CadIo.forEachLine(header, 5000) { raw ->
            val line = raw.trim()
            if (first) { first = false; if (line != "ply") return null }
            val t = line.split(Regex("\\s+"))
            when (t[0]) {
                "format" -> { format = t.getOrNull(1); version = t.getOrNull(2) }
                "comment" -> if (comments.size < 50) comments.add(line.removePrefix("comment").trim())
                "obj_info" -> if (objInfo.size < 50) objInfo.add(line.removePrefix("obj_info").trim())
                "element" -> if (elems.size < 64 && t.size >= 3) elems.add(Elem(t[1], t[2].toLongOrNull() ?: 0L))
                "property" -> {
                    val e = elems.lastOrNull()
                    if (e != null && e.props.size < 256) {
                        if (t.getOrNull(1) == "list" && t.size >= 5) e.props.add(Prop(t[4], t[3], t[2]))
                        else if (t.size >= 3) e.props.add(Prop(t[2], t[1], null))
                    }
                }
            }
        }
        val fmt = format ?: return null
        val le = fmt == "binary_little_endian"
        val f = CadFacts()
        f.add("Header", "Format", fmt + (version?.let { " $it" } ?: ""))
        for (e in elems) {
            f.add("Elements", "Element ${e.name}", CadIo.count(e.count) + " [" + e.props.joinToString(", ") { p ->
                if (p.listCount != null) "list ${p.listCount} ${p.type} ${p.name}" else "${p.type} ${p.name}"
            } + "]")
        }
        f.add("Header", "Comments", comments.joinToString(" | "))
        f.add("Header", "Object info", objInfo.joinToString(" | "))
        val known = fmt == "ascii" || fmt == "binary_little_endian" || fmt == "binary_big_endian"
        if (!known) f.warn("Unknown PLY format '$fmt'")

        val vert = elems.firstOrNull { it.name == "vertex" }
        val b = CadBounds()
        var scanned = 0L
        if (known && vert != null && vert.count > 0) {
            val xi = vert.props.indexOfFirst { it.name == "x" && it.listCount == null }
            val yi = vert.props.indexOfFirst { it.name == "y" && it.listCount == null }
            val zi = vert.props.indexOfFirst { it.name == "z" && it.listCount == null }
            if (xi >= 0 && yi >= 0 && zi >= 0) {
                if (fmt == "ascii") {
                    if (elems.first() === vert) {
                        val body = CadIo.latin1(bytes, bodyStart)
                        var n = 0L
                        CadIo.forEachLine(body, minOf(vert.count, 2_000_000L).toInt()) { line ->
                            val p = line.trim().split(Regex("\\s+"))
                            val x = p.getOrNull(xi)?.toDoubleOrNull(); val y = p.getOrNull(yi)?.toDoubleOrNull(); val z = p.getOrNull(zi)?.toDoubleOrNull()
                            if (x != null && y != null && z != null) b.add(x, y, z)
                            n++
                        }
                        scanned = n
                    }
                } else if (elems.first() === vert && vert.props.all { it.listCount == null && typeSize(it.type) > 0 }) {
                    val offs = IntArray(vert.props.size)
                    var row = 0
                    for ((k, p) in vert.props.withIndex()) { offs[k] = row; row += typeSize(p.type) }
                    if (row > 0) {
                        val avail = (bytes.size - bodyStart) / row
                        val n = minOf(vert.count, avail.toLong()).toInt()
                        for (r in 0 until n) {
                            val o = bodyStart + r * row
                            b.add(readNum(bytes, o + offs[xi], vert.props[xi].type, le), readNum(bytes, o + offs[yi], vert.props[yi].type, le), readNum(bytes, o + offs[zi], vert.props[zi].type, le))
                        }
                        scanned = n.toLong()
                    }
                }
            }
        }
        b.addFacts(f, "Geometry")
        if (b.n > 0 && scanned < vert!!.count) f.warn("Bounding box covers $scanned of ${vert.count} vertices (file truncated or read budget reached)")
        if (b.bad > 0) f.warn("${b.bad} vertices were non-finite and were ignored")

        // size validation for fixed-size binary bodies
        if (known && fmt != "ascii" && bytes.size.toLong() == source.size) {
            if (elems.all { e -> e.props.all { it.listCount == null && typeSize(it.type) > 0 } }) {
                var total = 0L
                for (e in elems) total += e.count * e.props.sumOf { typeSize(it.type) }.toLong().coerceAtLeast(0)
                val actual = source.size - bodyStart
                if (actual < total) f.warn("Body is $actual bytes but the header declares $total (truncated file)")
                else if (actual > total) f.warn("${actual - total} unexpected bytes after the declared elements")
                else f.add("Header", "Size check", "body size matches declared elements")
            }
        }
        val vc = vert?.count
        val fc = elems.firstOrNull { it.name == "face" }?.count
        return InspectionResult("ply", "PLY mesh", "PLY ($fmt)" + (vc?.let { ", ${CadIo.count(it)} vertices" } ?: "") + (fc?.let { ", ${CadIo.count(it)} faces" } ?: ""), f.list, f.warnings)
    }
}
