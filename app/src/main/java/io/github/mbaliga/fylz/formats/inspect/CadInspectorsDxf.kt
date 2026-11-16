package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

private val cadInsUnits = mapOf(
    0 to "Unitless", 1 to "Inches", 2 to "Feet", 3 to "Miles", 4 to "Millimeters", 5 to "Centimeters",
    6 to "Meters", 7 to "Kilometers", 8 to "Microinches", 9 to "Mils", 10 to "Yards", 11 to "Angstroms",
    12 to "Nanometers", 13 to "Microns", 14 to "Decimeters", 15 to "Decameters", 16 to "Hectometers",
    17 to "Gigameters", 18 to "Astronomical units", 19 to "Light years", 20 to "Parsecs",
)

/** AutoCAD DXF, ASCII (full) and binary (sentinel only). */
object CadDxfInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("dxf")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        run(source)
    } catch (t: Throwable) { null }

    private fun run(source: ByteSource): InspectionResult? {
        val bytes = CadIo.readUpTo(source, 0, FormatInspector.MAX_READ_BYTES)
        if (bytes.size < 4) return null
        if (Bytes.startsWith(bytes, "AutoCAD Binary DXF")) {
            val f = CadFacts()
            f.add("Format", "Encoding", "binary DXF")
            val sentinelOk = bytes.size >= 22 && Bytes.u8(bytes, 20) == 0x1A && Bytes.u8(bytes, 21) == 0
            f.add("Format", "Sentinel", if (sentinelOk) "AutoCAD Binary DXF, CR LF, SUB, NUL" else null)
            f.warn("Binary DXF content is not parsed; only the sentinel was identified")
            return InspectionResult("dxf", "Binary DXF", "Binary DXF drawing", f.list, f.warnings)
        }
        val text = CadIo.latin1(bytes, CadIo.skipBom(bytes))
        val lines = ArrayList<String>(0)
        // Pair reader over lines without materialising every line.
        var pos = 0
        fun nextLine(): String? {
            if (pos >= text.length) return null
            var e = text.indexOf('\n', pos)
            if (e < 0) e = text.length
            var end = e
            if (end > pos && text[end - 1] == '\r') end--
            val s = text.substring(pos, end)
            pos = e + 1
            return s
        }
        // First pair must be a numeric group code.
        val saved = pos
        val firstCode = nextLine()?.trim()?.toIntOrNull() ?: return null
        pos = saved
        if (firstCode != 0 && firstCode != 999) return null

        var section = ""
        var expectSection = false
        var curVar = ""
        val vars = HashMap<String, MutableList<Pair<Int, String>>>()
        var tableName = ""
        var tableNamePending = false
        var inLayer = false
        val layers = ArrayList<String>()
        var layerCount = 0
        var blocks = 0
        val hist = HashMap<String, Int>()
        var records = 0L
        var pairs = 0
        var malformed = false
        var sawSection = false
        var comments = ArrayList<String>()
        while (pairs < 4_000_000) {
            val cl = nextLine() ?: break
            val vl = nextLine() ?: break
            val code = cl.trim().toIntOrNull()
            if (code == null) { malformed = true; break }
            val value = vl.trim()
            pairs++
            if (code == 999) { if (comments.size < 5) comments.add(value); continue }
            if (code == 0 && value == "SECTION") { expectSection = true; sawSection = true; continue }
            if (expectSection && code == 2) { section = value; expectSection = false; curVar = ""; continue }
            if (code == 0 && value == "ENDSEC") { section = ""; continue }
            if (code == 0 && value == "EOF") break
            when (section) {
                "HEADER" -> {
                    if (code == 9) { curVar = value; if (vars.size < 400) vars[curVar] = ArrayList() }
                    else if (curVar.isNotEmpty()) vars[curVar]?.let { if (it.size < 8) it.add(code to value) }
                }
                "TABLES" -> {
                    if (code == 0 && value == "TABLE") { tableName = ""; tableNamePending = true; inLayer = false }
                    else if (code == 0 && value == "ENDTAB") { tableName = ""; inLayer = false }
                    else if (code == 2 && tableNamePending) { tableName = value; tableNamePending = false }
                    else if (code == 0) { tableNamePending = false; inLayer = tableName == "LAYER" && value == "LAYER"; if (inLayer) layerCount++ }
                    else if (code == 2 && inLayer) { if (layers.size < 40) layers.add(value); inLayer = false }
                }
                "BLOCKS" -> if (code == 0 && value == "BLOCK") blocks++
                "ENTITIES" -> if (code == 0) {
                    records++
                    if (hist.containsKey(value) || hist.size < 500) hist[value] = (hist[value] ?: 0) + 1
                }
            }
        }
        if (!sawSection && vars.isEmpty() && hist.isEmpty()) return null
        val f = CadFacts()
        fun v(name: String, code: Int = -1): String? =
            vars[name]?.firstOrNull { code < 0 || it.first == code }?.second
        val ver = v("\$ACADVER")
        f.add("Header", "ACADVER", ver)
        f.add("Header", "AutoCAD release", ver?.let { CadAcad.releases[it] })
        val insunits = v("\$INSUNITS")?.toIntOrNull()
        f.add("Header", "Drawing units (INSUNITS)", insunits?.let { (cadInsUnits[it] ?: "code $it") + " (code $it)" })
        v("\$MEASUREMENT")?.toIntOrNull()?.let { f.add("Header", "Measurement", when (it) { 0 -> "English"; 1 -> "Metric"; else -> "code $it" }) }
        f.add("Header", "Code page", v("\$DWGCODEPAGE"))
        f.add("Header", "Last saved by", v("\$LASTSAVEDBY"))
        f.add("Header", "Project name", v("\$PROJECTNAME"))
        fun pt(name: String): List<Double>? {
            val l = vars[name] ?: return null
            val x = l.firstOrNull { it.first == 10 }?.second?.toDoubleOrNull() ?: return null
            val y = l.firstOrNull { it.first == 20 }?.second?.toDoubleOrNull() ?: return null
            val z = l.firstOrNull { it.first == 30 }?.second?.toDoubleOrNull()
            return if (z != null) listOf(x, y, z) else listOf(x, y)
        }
        val mn = pt("\$EXTMIN"); val mx = pt("\$EXTMAX")
        if (mn != null && mx != null) {
            f.add("Extents", "Extents min", mn.joinToString(", ") { CadIo.num(it) })
            f.add("Extents", "Extents max", mx.joinToString(", ") { CadIo.num(it) })
            if (mn.size == mx.size) f.add("Extents", "Extents size", mn.indices.joinToString(", ") { CadIo.num(mx[it] - mn[it]) })
        }
        if (layerCount > 0) {
            f.add("Layers", "Layers", layerCount.toString())
            f.add("Layers", "Layer names", layers.joinToString(", "))
        }
        if (blocks > 0) f.add("Structure", "Blocks", blocks.toString())
        f.add("Entities", "Entity records", CadIo.count(records))
        val top = hist.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(15)
        f.add("Entities", "Entity types", top.joinToString(", ") { "${it.key} (${it.value})" })
        f.add("Header", "Comments", comments.joinToString(" | "))
        if (malformed) f.warn("Stopped at a line that is not a numeric group code")
        if (bytes.size < source.size) f.warn("Only the first 8 MiB were scanned; counts are lower bounds")
        if (ver != null && CadAcad.releases[ver] == null) f.warn("Unrecognised \$ACADVER '$ver'")
        val rel = ver?.let { CadAcad.releases[it] }
        return InspectionResult("dxf", "DXF drawing", "ASCII DXF" + (rel?.let { ", $it" } ?: "") + ", ${CadIo.count(records)} entity records", f.list, f.warnings)
    }
}

/** DWG: the version tag only. */
object CadDwgInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("dwg")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        val h = source.read(0, 6)
        val tag = CadIo.latin1(h)
        if (h.size < 6 || !Regex("AC10\\d\\d").matches(tag)) null
        else {
            val f = CadFacts()
            val rel = CadAcad.releases[tag]
            f.add("Header", "Version tag", tag)
            f.add("Header", "AutoCAD release", rel)
            f.add("File", "Size", Bytes.humanSize(source.size))
            if (rel == null) f.warn("Unrecognised DWG version tag '$tag'")
            f.warn("Only the version tag is read; drawing contents are not parsed")
            InspectionResult("dwg", "DWG drawing", "DWG, ${rel ?: tag}", f.list, f.warnings)
        }
    } catch (t: Throwable) { null }
}
