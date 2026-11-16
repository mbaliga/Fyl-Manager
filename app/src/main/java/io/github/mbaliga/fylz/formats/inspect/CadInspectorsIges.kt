package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** IGES (ANSI US PRO/IPO-100) fixed-column inspector: S, G, D, P and T sections. */
object CadIgesInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("iges")

    private val entityNames = mapOf(
        100 to "Circular Arc", 102 to "Composite Curve", 104 to "Conic Arc", 106 to "Copious Data",
        108 to "Plane", 110 to "Line", 112 to "Parametric Spline Curve", 114 to "Parametric Spline Surface",
        116 to "Point", 118 to "Ruled Surface", 120 to "Surface of Revolution", 122 to "Tabulated Cylinder",
        124 to "Transformation Matrix", 126 to "Rational B-Spline Curve", 128 to "Rational B-Spline Surface",
        130 to "Offset Curve", 140 to "Offset Surface", 141 to "Boundary", 142 to "Curve on a Parametric Surface",
        143 to "Bounded Surface", 144 to "Trimmed (Parametric) Surface", 150 to "Block", 152 to "Right Angular Wedge",
        154 to "Right Circular Cylinder", 156 to "Right Circular Cone Frustum", 158 to "Sphere", 160 to "Torus",
        162 to "Solid of Revolution", 164 to "Solid of Linear Extrusion", 168 to "Ellipsoid", 180 to "Boolean Tree",
        184 to "Solid Assembly", 186 to "Manifold Solid B-Rep Object", 190 to "Plane Surface",
        192 to "Right Circular Cylindrical Surface", 194 to "Right Circular Conical Surface",
        196 to "Spherical Surface", 198 to "Toroidal Surface", 202 to "Angular Dimension", 212 to "General Note",
        214 to "Leader (Arrow)", 216 to "Linear Dimension", 222 to "Diameter Dimension", 308 to "Subfigure Definition",
        314 to "Color Definition", 402 to "Associativity Instance", 406 to "Property", 408 to "Singular Subfigure Instance",
        410 to "View", 412 to "Rectangular Array Subfigure Instance", 414 to "Circular Array Subfigure Instance",
        502 to "Vertex", 504 to "Edge", 508 to "Loop", 510 to "Face", 514 to "Shell",
    )
    private val units = mapOf(
        1 to "inches", 2 to "millimeters", 4 to "feet", 5 to "miles", 6 to "meters", 7 to "kilometers",
        8 to "mils", 9 to "microns", 10 to "centimeters", 11 to "microinches",
    )
    private val versions = mapOf(
        1 to "1.0", 2 to "ANSI Y14.26M-1981", 3 to "2.0", 4 to "3.0", 5 to "ANSI Y14.26M-1987",
        6 to "4.0", 7 to "ANSI Y14.26M-1989", 8 to "5.0", 9 to "5.1", 10 to "5.2", 11 to "5.3",
    )

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        run(source)
    } catch (t: Throwable) { null }

    private fun run(source: ByteSource): InspectionResult? {
        val bytes = CadIo.readUpTo(source, 0, FormatInspector.MAX_READ_BYTES)
        if (bytes.size < 80) return null
        val text = CadIo.latin1(bytes)
        val start = StringBuilder()
        val global = StringBuilder()
        val hist = HashMap<Int, Int>()
        var s = 0; var g = 0; var d = 0; var p = 0
        var term: String? = null
        var firstDirLine = true
        var dirLines = 0
        var sawSection = false
        var badLines = 0
        CadIo.forEachLine(text, 400_000) { line ->
            if (line.length < 73) { if (line.isNotBlank()) badLines++; return@forEachLine }
            when (line[72]) {
                'S' -> { s++; sawSection = true; if (start.length < 4000) start.append(line, 0, 72.coerceAtMost(line.length)).append('\n') }
                'G' -> { g++; if (global.length < 8000) global.append(line, 0, 72) }
                'D' -> {
                    d++
                    if (d % 2 == 1) {
                        val t = line.substring(0, 8).trim().toIntOrNull()
                        if (t != null && hist.size < 2000) hist[t] = (hist[t] ?: 0) + 1
                    }
                }
                'P' -> p++
                'T' -> term = line.substring(0, 72)
                else -> badLines++
            }
        }
        if (!sawSection || g == 0) return null
        val f = CadFacts()
        val startText = start.toString().trimEnd()
        f.add("Start", "Start section", CadIo.clip(startText.replace('\n', ' ').trim(), 300))
        val params = parseGlobal(global.toString())
        fun gp(i: Int): String? = params.getOrNull(i - 1)?.trim()?.takeIf { it.isNotEmpty() }
        f.add("Global", "Product id (sender)", gp(3))
        f.add("Global", "File name", gp(4))
        f.add("Global", "System id", gp(5))
        f.add("Global", "Preprocessor version", gp(6))
        f.add("Global", "Product id (receiver)", gp(12))
        f.add("Global", "Model space scale", gp(13))
        val flag = gp(14)?.toIntOrNull()
        if (flag != null) {
            val u = if (flag == 3) gp(15) else units[flag]
            f.add("Global", "Units", (u ?: "unknown") + " (flag $flag)")
        }
        f.add("Global", "Units name", gp(15))
        f.add("Global", "Generated", gp(18))
        f.add("Global", "Author", gp(21))
        f.add("Global", "Organization", gp(22))
        val ver = gp(23)?.toIntOrNull()
        f.add("Global", "IGES version", ver?.let { versions[it] ?: "code $it" })
        f.add("Global", "Drafting standard code", gp(24))
        f.add("Global", "Model created", gp(25))
        f.add("Global", "Application protocol", gp(26))

        val entries = d / 2
        f.add("Structure", "Lines (S/G/D/P)", "$s / $g / $d / $p")
        f.add("Structure", "Directory entries", CadIo.count(entries.toLong()))
        f.add("Structure", "Distinct entity types", hist.size.toString())
        val top = hist.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).take(12)
        f.add("Entities", "Top entity types", top.joinToString(", ") { "${it.key} ${entityNames[it.key] ?: "(unnamed in this table)"} (${it.value})" })

        val w = f.warnings
        if (source.size > bytes.size) f.warn("Only the first 8 MiB were scanned; counts are lower bounds")
        else {
            val t = term
            if (t == null) f.warn("No Terminate (T) section; file may be truncated")
            else {
                val expect = "S" + "%07d".format(s) + "G" + "%07d".format(g) + "D" + "%07d".format(d) + "P" + "%07d".format(p)
                if (t.take(32) != expect) w.add("Terminate record (${t.take(32).trim()}) disagrees with counted lines ($expect)")
            }
            if (d % 2 != 0) f.warn("Directory section has an odd number of lines")
        }
        val title = gp(3) ?: gp(4) ?: "IGES file"
        return InspectionResult("iges", title, "IGES ${ver?.let { versions[it] }?.let { "v$it, " } ?: ""}$entries directory entries", f.list, f.warnings, startText.takeIf { it.isNotBlank() }?.let { CadIo.clip(it, FormatInspector.MAX_TEXT_CHARS) })
    }

    /** Parses the Global section string (Hollerith strings nHxxxx, delimiters from the first two fields). */
    internal fun parseGlobal(g: String): List<String?> {
        var i = 0
        var pd = ','
        var rd = ';'
        val out = ArrayList<String?>()
        // first parameter: delimiter definition (empty = default ',')
        if (!g.startsWith(",")) {
            val h = g.indexOf('H')
            val n = if (h > 0) g.substring(0, h).trim().toIntOrNull() else null
            if (n == 1 && h + 1 < g.length) { pd = g[h + 1]; i = h + 2 } else return out
        }
        out.add(pd.toString())
        if (i < g.length && g[i] == pd) i++
        // second parameter: record delimiter (empty = default ';')
        if (i < g.length && g[i] == pd) { i++ }
        else {
            val h = g.indexOf('H', i)
            val n = if (h > i) g.substring(i, h).trim().toIntOrNull() else null
            if (n == 1 && h + 1 < g.length) { rd = g[h + 1]; i = h + 2 } else return out
            if (i < g.length && g[i] == pd) i++
        }
        out.add(rd.toString())
        var guard = 0
        while (i < g.length && guard++ < 100) {
            // skip blanks
            while (i < g.length && g[i] == ' ') i++
            if (i >= g.length) break
            if (g[i] == rd) break
            if (g[i] == pd) { out.add(null); i++; continue }
            var j = i
            while (j < g.length && g[j].isDigit()) j++
            if (j > i && j < g.length && g[j] == 'H') {
                val n = g.substring(i, j).toIntOrNull() ?: 0
                val st = j + 1
                val en = minOf(g.length, st + minOf(n, 10000))
                out.add(g.substring(st, en))
                i = en
            } else {
                var k = i
                while (k < g.length && g[k] != pd && g[k] != rd) k++
                out.add(g.substring(i, k).trim())
                i = k
            }
            while (i < g.length && g[i] == ' ') i++
            if (i < g.length && g[i] == pd) i++ else break
        }
        return out
    }
}
