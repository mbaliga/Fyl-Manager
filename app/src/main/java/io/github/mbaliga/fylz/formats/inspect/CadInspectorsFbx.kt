package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Autodesk FBX, binary and ASCII. */
object CadFbxInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("fbx")

    private const val MAGIC = "Kaydara FBX Binary  "
    private class Node(val name: String, val end: Long, val propsStart: Long, val propsLen: Long, val numProps: Long)

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        val head = source.read(0, 32)
        if (Bytes.startsWith(head, MAGIC) && head.size >= 27) binary(source, head) else ascii(source)
    } catch (t: Throwable) { null }

    private class Reader(val src: ByteSource, val wide: Boolean) {
        var used = 0L
        fun node(off: Long, limit: Long): Node? {
            val hdr = if (wide) 25 else 13
            if (off < 0 || off + hdr > limit || used > FormatInspector.MAX_READ_BYTES) return null
            val b = src.read(off, hdr + 255)
            used += b.size
            if (b.size < hdr) return null
            val end: Long; val num: Long; val plen: Long
            if (wide) { end = Bytes.u64le(b, 0); num = Bytes.u64le(b, 8); plen = Bytes.u64le(b, 16) }
            else { end = Bytes.u32le(b, 0); num = Bytes.u32le(b, 4); plen = Bytes.u32le(b, 8) }
            if (end == 0L && num == 0L && plen == 0L) return null
            val nl = Bytes.u8(b, hdr - 1)
            if (hdr + nl > b.size) return null
            if (end <= off || end > limit || end < 0) return null
            val name = CadIo.latin1(b, hdr, nl)
            val ps = off + hdr + nl
            if (plen < 0 || plen > end - ps) return null
            return Node(name, end, ps, plen, num)
        }

        fun children(parent: Node, cap: Int = 20000): List<Node> {
            val out = ArrayList<Node>()
            var off = parent.propsStart + parent.propsLen
            while (out.size < cap) {
                val n = node(off, parent.end) ?: break
                out.add(n)
                off = n.end
            }
            return out
        }

        fun firstString(n: Node): String? {
            if (n.numProps < 1 || n.propsLen < 5) return null
            val b = src.read(n.propsStart, minOf(n.propsLen, 5000L).toInt())
            used += b.size
            if (Bytes.u8(b, 0) != 'S'.code) return null
            val l = Bytes.u32le(b, 1)
            if (l > b.size - 5) return null
            return CadIo.latin1(b, 5, l.toInt()).filter { it.code >= 0x20 }
        }

        fun firstInt(n: Node): Long? {
            if (n.numProps < 1 || n.propsLen < 5) return null
            val b = src.read(n.propsStart, 9)
            used += b.size
            return when (Bytes.u8(b, 0).toChar()) {
                'I' -> Bytes.u32le(b, 1).toInt().toLong()
                'L' -> if (b.size >= 9) Bytes.u64le(b, 1) else null
                'Y' -> Bytes.u16le(b, 1).toShort().toLong()
                else -> null
            }
        }
    }

    private fun binary(source: ByteSource, head: ByteArray): InspectionResult? {
        val version = Bytes.u32le(head, 23)
        val f = CadFacts()
        f.add("Header", "Encoding", "binary")
        f.add("Header", "Version", "${version / 1000}.${(version % 1000) / 100} ($version)")
        val wide = version >= 7500
        f.add("Header", "Node record size", if (wide) "64-bit offsets (7.5 and later)" else "32-bit offsets")
        if (version < 5000 || version > 9999) f.warn("Unusual FBX version number $version")
        val r = Reader(source, wide)
        var off = 27L
        val top = ArrayList<String>()
        var creator: String? = null
        var fbxVersion: Long? = null
        val stamp = LinkedHashMap<String, Long>()
        val objects = HashMap<String, Int>()
        var objectCount = 0
        var guard = 0
        var ended = false
        while (guard++ < 200) {
            val n = r.node(off, source.size)
            if (n == null) { ended = true; break }
            if (top.size < 40) top.add(n.name)
            if (n.name == "FBXHeaderExtension") {
                for (c in r.children(n, 200)) {
                    when (c.name) {
                        "Creator" -> creator = r.firstString(c)
                        "FBXVersion" -> fbxVersion = r.firstInt(c)
                        "CreationTimeStamp" -> for (t in r.children(c, 20)) r.firstInt(t)?.let { stamp[t.name] = it }
                    }
                }
            } else if (n.name == "Objects") {
                for (c in r.children(n, 15000)) {
                    objectCount++
                    if (objects.containsKey(c.name) || objects.size < 100) objects[c.name] = (objects[c.name] ?: 0) + 1
                }
            }
            off = n.end
        }
        f.add("Header", "Creator", creator)
        f.add("Header", "FBXVersion field", fbxVersion?.toString())
        if (stamp.containsKey("Year")) {
            fun p(k: String, w: Int) = (stamp[k] ?: 0L).toString().padStart(w, '0')
            f.add("Header", "Created", "${p("Year", 4)}-${p("Month", 2)}-${p("Day", 2)} ${p("Hour", 2)}:${p("Minute", 2)}:${p("Second", 2)}")
        }
        f.add("Structure", "Top-level nodes", top.joinToString(", "))
        if (objectCount > 0) {
            f.add("Structure", "Object records", CadIo.count(objectCount.toLong()))
            val hist = objects.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            f.add("Structure", "Object types", hist.joinToString(", ") { "${it.key} (${it.value})" })
        }
        if (top.isEmpty()) f.warn("No readable top-level nodes")
        if (r.used > FormatInspector.MAX_READ_BYTES) f.warn("Read budget reached; node counts may be incomplete")
        return InspectionResult("fbx", creator?.takeIf { it.isNotBlank() } ?: "Binary FBX", "Binary FBX ${version / 1000}.${(version % 1000) / 100}" + (creator?.let { ", $it" } ?: ""), f.list, f.warnings)
    }

    private fun ascii(source: ByteSource): InspectionResult? {
        val bytes = CadIo.readUpTo(source, 0, FormatInspector.MAX_READ_BYTES - 32)
        val text = CadIo.utf8(bytes, CadIo.skipBom(bytes))
        val first = text.lineSequence().firstOrNull()?.trim() ?: return null
        val m = Regex("^; FBX (\\d+)\\.(\\d+)\\.(\\d+) project file").find(first) ?: return null
        val f = CadFacts()
        f.add("Header", "Encoding", "ASCII")
        f.add("Header", "Version", "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}")
        val headText = text.take(65536)
        val creator = Regex("(?m)^[ \\t]*Creator:\\s*\"([^\"]*)\"").find(headText)?.groupValues?.get(1)
        f.add("Header", "Creator", creator)
        f.add("Header", "FBXVersion field", Regex("(?m)^[ \\t]*FBXVersion:\\s*(\\d+)").find(headText)?.groupValues?.get(1))
        val counts = LinkedHashMap<String, Int>()
        val re = Regex("(?m)^[ \\t]*(Geometry|Model|Material|Texture|AnimationStack|Deformer|NodeAttribute|Video):\\s*\\d+,")
        for (k in re.findAll(text)) { val n = k.groupValues[1]; counts[n] = (counts[n] ?: 0) + 1 }
        if (counts.isNotEmpty()) f.add("Structure", "Objects", counts.entries.joinToString(", ") { "${it.key} (${it.value})" })
        if (bytes.size < source.size) f.warn("Only the first 8 MiB were scanned; counts are lower bounds")
        return InspectionResult("fbx", creator?.takeIf { it.isNotBlank() } ?: "ASCII FBX", "ASCII FBX ${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}", f.list, f.warnings)
    }
}
