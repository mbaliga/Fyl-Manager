package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** HEIF family still images: HEIC and AVIF, via the ISOBMFF 'meta' box. */
internal object IsobmffImageInspector : SafeInspector() {
    override val formatIds = setOf("heic", "avif")
    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "mif2")
    private val AVIF_BRANDS = setOf("avif", "avis")
    private const val MAX_META = 4 * 1024 * 1024

    private class Box(val type: String, val body: Int, val end: Int)

    private fun children(d: ByteArray, start: Int, end: Int, max: Int = 512): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= end && out.size < max) {
            var sz = Bytes.u32be(d, p)
            var hdr = 8
            if (sz == 1L) { sz = Bytes.u64be(d, p + 8); hdr = 16 } else if (sz == 0L) sz = (end - p).toLong()
            if (sz < hdr) break
            val e = minOf(end.toLong(), p + sz).toInt()
            out.add(Box(Bytes.ascii(d, p + 4, 4), p + hdr, e))
            p = e
        }
        return out
    }

    private fun brandLabel(b: String) = when (b) {
        "heic" -> "HEIC (HEVC still image)"; "heix" -> "HEIC (HEVC, extended profiles)"
        "hevc" -> "HEVC image sequence"; "hevx" -> "HEVC image sequence (extended)"
        "mif1" -> "HEIF image (generic)"; "msf1" -> "HEIF image sequence (generic)"
        "avif" -> "AVIF still image"; "avis" -> "AVIF image sequence"
        else -> b
    }

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val head = source.read(0, 16)
        if (head.size < 12 || !Bytes.startsWith(head, "ftyp", 4)) return null
        val ftypSize = Bytes.u32be(head, 0)
        if (ftypSize < 16 || ftypSize > 4096) return null
        val ftyp = source.read(0, ftypSize.toInt())
        val major = Bytes.ascii(ftyp, 8, 4)
        val minor = Bytes.u32be(ftyp, 12)
        val compat = ArrayList<String>()
        var o = 16
        while (o + 4 <= ftyp.size && compat.size < 64) { compat.add(Bytes.ascii(ftyp, o, 4)); o += 4 }
        val all = listOf(major) + compat
        if (all.none { it in HEIF_BRANDS || it in AVIF_BRANDS }) return null
        val isAvif = all.any { it in AVIF_BRANDS }
        val ext = Img.ext(fileName)
        val id = when (ext) { "avif" -> "avif"; "heic", "heif", "hif" -> "heic"; else -> if (isAvif) "avif" else "heic" }

        val warnings = ArrayList<String>()
        val size = source.size
        var pos = ftypSize
        var boxes = 0
        var meta: ByteArray? = null
        var hasMoov = false
        while (boxes++ < 64 && pos + 8 <= size) {
            val h = source.read(pos, 16)
            if (h.size < 8) break
            var sz = Bytes.u32be(h, 0); var hdr = 8
            val type = Bytes.ascii(h, 4, 4)
            if (sz == 1L) { sz = Bytes.u64be(h, 8); hdr = 16 } else if (sz == 0L) sz = size - pos
            if (sz < hdr) { warnings.add("Invalid box size at offset $pos"); break }
            if (type == "meta" && meta == null) {
                val len = minOf(sz - hdr, MAX_META.toLong()).toInt()
                meta = source.read(pos + hdr, len)
                if (sz - hdr > MAX_META) warnings.add("meta box larger than 4 MiB; only the first 4 MiB was read")
            }
            if (type == "moov") hasMoov = true
            if (sz > size - pos) break
            pos += sz
        }

        if (meta == null && all.all { it == "mif1" || it == "msf1" || it == "mif2" }) return null
        val f = FactList()
        f.add("Container", "Major brand", brandLabel(major))
        if (compat.isNotEmpty()) f.add("Container", "Compatible brands", compat.joinToString(", "))
        f.add("Container", "Minor version", "$minor")
        var summary: String? = if (isAvif) "AVIF image" else "HEIC image"

        if (meta == null) {
            warnings.add("No meta box found; image properties unavailable")
        } else {
            val m = meta
            val top = children(m, 4, m.size)
            var primary = -1L
            val itemTypes = LinkedHashMap<Long, String>()
            var props: List<Box> = emptyList()
            val assoc = HashMap<Long, List<Int>>()
            for (b in top) {
                when (b.type) {
                    "pitm" -> primary = if (Bytes.u8(m, b.body) == 0) Bytes.u16be(m, b.body + 4).toLong() else Bytes.u32be(m, b.body + 4)
                    "iinf" -> {
                        val ver = Bytes.u8(m, b.body)
                        val start = b.body + if (ver == 0) 6 else 8
                        for (c in children(m, start, b.end, 2000)) if (c.type == "infe") {
                            val v = Bytes.u8(m, c.body)
                            if (v == 2) itemTypes[Bytes.u16be(m, c.body + 4).toLong()] = Bytes.ascii(m, c.body + 8, 4)
                            else if (v == 3) itemTypes[Bytes.u32be(m, c.body + 4)] = Bytes.ascii(m, c.body + 10, 4)
                        }
                    }
                    "iprp" -> for (c in children(m, b.body, b.end)) {
                        if (c.type == "ipco") props = children(m, c.body, c.end)
                        if (c.type == "ipma") {
                            val ver = Bytes.u8(m, c.body); val wide = (Bytes.u8(m, c.body + 3) and 1) != 0
                            val cnt = minOf(Bytes.u32be(m, c.body + 4), 4096L).toInt()
                            var p = c.body + 8
                            for (i in 0 until cnt) {
                                if (p + 5 > c.end) break
                                val item = if (ver == 0) Bytes.u16be(m, p).toLong().also { p += 2 } else Bytes.u32be(m, p).also { p += 4 }
                                val n = Bytes.u8(m, p); p++
                                val list = ArrayList<Int>()
                                for (k in 0 until n) {
                                    if (p + (if (wide) 2 else 1) > c.end) break
                                    if (wide) { list.add(Bytes.u16be(m, p) and 0x7FFF); p += 2 } else { list.add(Bytes.u8(m, p) and 0x7F); p++ }
                                }
                                assoc[item] = list
                            }
                        }
                    }
                }
            }
            val imageTypes = setOf("hvc1", "hev1", "av01", "avc1", "grid", "iovl", "jpeg", "vvc1")
            val counts = java.util.TreeMap<String, Int>()
            for (t in itemTypes.values) counts.merge(t, 1, Int::plus)
            val imageItems = itemTypes.values.count { it in imageTypes }
            if (itemTypes.isNotEmpty()) {
                f.add("Items", "Items", itemTypes.size.toString())
                f.add("Items", "Image items", imageItems.toString())
                f.add("Items", "Item types", counts.entries.take(12).joinToString(", ") { "${it.key}×${it.value}" })
            }
            if (primary >= 0) f.add("Items", "Primary item ID", "$primary")
            fun propsOf(item: Long): List<Box> = (assoc[item] ?: emptyList()).mapNotNull { props.getOrNull(it - 1) }
            fun ispe(b: Box): Pair<Long, Long>? = if (b.type == "ispe" && b.end - b.body >= 12) Pair(Bytes.u32be(m, b.body + 4), Bytes.u32be(m, b.body + 8)) else null
            val primProps = propsOf(primary)
            var dim = primProps.firstNotNullOfOrNull { ispe(it) }
            var fromPrimary = dim != null
            if (dim == null) dim = props.mapNotNull { ispe(it) }.maxByOrNull { it.first * it.second }
            if (dim != null) {
                f.add("Image", if (fromPrimary) "Dimensions" else "Dimensions (largest ispe)", Img.dims(dim.first, dim.second))
                summary = (if (isAvif) "AVIF " else "HEIC ") + Img.dims(dim.first, dim.second)
            }
            val src = if (fromPrimary) primProps else props
            for (b in src) when (b.type) {
                "pixi" -> {
                    val n = Bytes.u8(m, b.body + 4)
                    if (n in 1..8) f.add("Image", "Channels / bit depth", "$n channel" + (if (n > 1) "s" else "") + ", " +
                        (0 until n).joinToString("/") { Bytes.u8(m, b.body + 5 + it).toString() } + " bits")
                }
                "irot" -> f.add("Image", "Rotation", "${(Bytes.u8(m, b.body) and 3) * 90}° counter-clockwise")
                "colr" -> {
                    val ct = Bytes.ascii(m, b.body, 4)
                    f.add("Colour", "Colour info", when (ct) {
                        "nclx" -> "nclx (primaries ${Bytes.u16be(m, b.body + 4)}, transfer ${Bytes.u16be(m, b.body + 6)}, matrix ${Bytes.u16be(m, b.body + 8)})"
                        "rICC", "prof" -> "ICC profile ($ct)"
                        else -> ct
                    })
                }
                "av1C" -> {
                    val b1 = Bytes.u8(m, b.body + 1); val b2 = Bytes.u8(m, b.body + 2)
                    val depth = if (b2 and 0x40 != 0) (if (b2 and 0x20 != 0) 12 else 10) else 8
                    f.add("Codec", "Codec", "AV1 (profile ${b1 shr 5}, level index ${b1 and 0x1F}, $depth-bit${if (b2 and 0x10 != 0) ", monochrome" else ""})")
                }
                "hvcC" -> f.add("Codec", "Codec", "HEVC (profile idc ${Bytes.u8(m, b.body + 1) and 0x1F})")
            }
            if (fromPrimary && primProps.none { it.type == "hvcC" || it.type == "av1C" }) {
                if (props.any { it.type == "av1C" }) f.add("Codec", "Codec", "AV1")
                else if (props.any { it.type == "hvcC" }) f.add("Codec", "Codec", "HEVC")
            }
        }
        if (hasMoov) f.add("Container", "Image sequence", "yes (moov box present)")
        return InspectionResult(id, Img.title(fileName, if (isAvif) "AVIF image" else "HEIC image"), summary, f.list, warnings)
    }
}
