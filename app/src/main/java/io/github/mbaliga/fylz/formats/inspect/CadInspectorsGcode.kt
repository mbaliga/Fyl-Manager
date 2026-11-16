package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** G-code (3D printing and CNC): command statistics, toolpath bounds, slicer comments. */
object CadGcodeInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("gcode")

    private val argRe = Regex("[A-Za-z][-+]?[0-9.]*")
    private val textMs = setOf(23, 28, 30, 32, 117, 118, 20, 21, 22, 24, 25, 26, 27, 29, 33, 34)
    private const val HEAD = 6 * 1024 * 1024
    private const val TAIL = 2 * 1024 * 1024
    private val slicers = listOf(
        "PrusaSlicer", "SuperSlicer", "OrcaSlicer", "BambuStudio", "Bambu Studio", "Cura_SteamEngine", "Cura",
        "Simplify3D", "Slic3r", "ideaMaker", "KISSlicer", "MatterSlicer",
    )
    private val commentKeys = listOf(
        Regex("^TIME:\\s*([0-9.]+)\\s*$") to "Print time (Cura, seconds)",
        Regex("^Filament used:\\s*(.+)$") to "Filament used",
        Regex("^Layer height:\\s*(.+)$") to "Layer height",
        Regex("^layer_height\\s*=\\s*(.+)$") to "Layer height",
        Regex("^estimated printing time \\(normal mode\\)\\s*=\\s*(.+)$") to "Estimated print time",
        Regex("^filament used \\[mm\\]\\s*=\\s*(.+)$") to "Filament length (mm)",
        Regex("^filament used \\[cm3\\]\\s*=\\s*(.+)$") to "Filament volume (cm3)",
        Regex("^(?:total )?filament used \\[g\\]\\s*=\\s*(.+)$") to "Filament weight (g)",
        Regex("(?:^|;\\s*)total estimated time:\\s*([0-9dhms ]+)") to "Estimated print time",
        Regex("^total filament length \\[mm\\]\\s*:\\s*(.+)$") to "Filament length (mm)",
        Regex("^total filament weight \\[g\\]\\s*:\\s*(.+)$") to "Filament weight (g)",
        Regex("^Build time:\\s*(.+)$") to "Estimated print time",
        Regex("^Filament length:\\s*(.+)$") to "Filament length",
        Regex("^Plastic weight:\\s*(.+)$") to "Filament weight",
        Regex("^Plastic volume:\\s*(.+)$") to "Filament volume",
    )

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        run(source)
    } catch (t: Throwable) { null }

    private class State {
        val cmds = HashMap<String, Int>()
        var lines = 0L
        var cmdLines = 0L
        var commentLines = 0L
        val pos = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
        var relative = false
        val b = CadBounds()
        var moves = 0L
        var layerMarkers = 0L
        var layerCount: String? = null
        var gLines = 0L
    }

    private fun run(source: ByteSource): InspectionResult? {
        val head = CadIo.readUpTo(source, 0, HEAD)
        if (head.isEmpty()) return null
        for (i in 0 until minOf(head.size, 4096)) if (head[i].toInt() == 0) return null
        val text = CadIo.utf8(head, CadIo.skipBom(head))
        val st = State()
        val found = LinkedHashMap<String, String>()
        var slicer: String? = null
        var commentsSeen = 0

        fun comment(c: String) {
            val t = c.trim().trimStart(';').trim()
            if (t.isEmpty()) return
            commentsSeen++
            if (slicer == null && commentsSeen <= 300) {
                val low = t.lowercase()
                for (s in slicers) if (Regex("(^|[^a-z])" + Regex.escape(s.lowercase()) + "([^a-z]|$)").containsMatchIn(low)) { slicer = s.replace('_', ' ').let { if (it == "Cura SteamEngine") "Cura" else it }; found["Slicer comment"] = CadIo.clip(t, 120); break }
            }
            if (t.startsWith("LAYER:")) st.layerMarkers++
            else if (t == "LAYER_CHANGE") st.layerMarkers++
            else if (t.startsWith("LAYER_COUNT:")) st.layerCount = t.substringAfter(':').trim()
            for ((re, label) in commentKeys) {
                val m = re.find(t) ?: continue
                if (!found.containsKey(label)) found[label] = m.groupValues[1].trim()
                break
            }
        }

        CadIo.forEachLine(text) { raw ->
            st.lines++
            var line = raw
            val semi = line.indexOf(';')
            if (semi >= 0) { st.commentLines++; comment(line.substring(semi)); line = line.substring(0, semi) }
            // strip (parenthesis comments)
            if (line.indexOf('(') >= 0) {
                val sb = StringBuilder(line.length)
                var inP = false
                for (ch in line) { if (ch == '(') inP = true else if (ch == ')' && inP) { inP = false; sb.append(' ') } else if (!inP) sb.append(ch) }
                line = sb.toString()
            }
            line = line.trim()
            if (line.isEmpty()) return@forEachLine
            var words = line.split(Regex("\\s+"))
            if (words[0].length > 1 && (words[0][0] == 'N' || words[0][0] == 'n') && words[0].substring(1).all { it.isDigit() }) words = words.drop(1)
            val w = words.firstOrNull() ?: return@forEachLine
            val letter = w[0].uppercaseChar()
            if (letter !in "GMT" || w.length < 2) return@forEachLine
            val numPart = w.substring(1).substringBefore('*')
            val dot = numPart.indexOf('.')
            val ip = (if (dot >= 0) numPart.substring(0, dot) else numPart).toIntOrNull() ?: return@forEachLine
            val code = letter.toString() + ip + (if (dot >= 0) numPart.substring(dot) else "")
            val textOk = letter == 'M' && ip in textMs
            if (!textOk && !words.drop(1).all { argRe.matches(it.substringBefore('*')) }) return@forEachLine
            st.cmdLines++
            if (letter == 'G') st.gLines++
            if (st.cmds.containsKey(code) || st.cmds.size < 500) st.cmds[code] = (st.cmds[code] ?: 0) + 1
            if (letter == 'G') {
                when (code) {
                    "G90" -> st.relative = false
                    "G91" -> st.relative = true
                    "G28" -> {
                        val axes = words.drop(1).map { it[0].uppercaseChar() }.filter { it in "XYZ" }
                        for (a in if (axes.isEmpty()) listOf('X', 'Y', 'Z') else axes) st.pos["XYZ".indexOf(a)] = Double.NaN
                    }
                    "G0", "G1", "G92" -> {
                        var touched = false
                        for (word in words.drop(1)) {
                            val ai = "XYZ".indexOf(word[0].uppercaseChar())
                            if (ai < 0) continue
                            val v = word.substring(1).toDoubleOrNull() ?: continue
                            touched = true
                            if (code == "G92") st.pos[ai] = v
                            else if (st.relative) { if (!st.pos[ai].isNaN()) st.pos[ai] += v }
                            else st.pos[ai] = v
                        }
                        if (code != "G92") {
                            st.moves++
                            if (touched && !st.pos[0].isNaN() && !st.pos[1].isNaN() && !st.pos[2].isNaN()) st.b.add(st.pos[0], st.pos[1], st.pos[2])
                        }
                    }
                }
            }
        }
        if (st.cmdLines == 0L || (st.gLines == 0L && st.cmdLines < 3)) return null
        var partial = source.size > head.size
        if (partial && source.size > HEAD + TAIL.toLong()) {
            val tailStart = source.size - TAIL
            val tb = CadIo.readUpTo(source, tailStart, TAIL)
            val tt = CadIo.utf8(tb)
            var first = true
            CadIo.forEachLine(tt) { l -> if (first) { first = false } else { val s = l.indexOf(';'); if (s >= 0) comment(l.substring(s)) } }
        } else if (partial) {
            val tb = CadIo.readUpTo(source, head.size.toLong(), TAIL)
            partial = head.size + tb.size < source.size
            CadIo.forEachLine(CadIo.utf8(tb)) { l -> val s = l.indexOf(';'); if (s >= 0) comment(l.substring(s)) }
        }

        val f = CadFacts()
        f.add("Slicer", "Slicer", slicer)
        f.add("Slicer", "Slicer comment", found["Slicer comment"])
        for ((k, v) in found) if (k != "Slicer comment") f.add("Print estimates (from comments)", k, v)
        f.add("Commands", "Lines", CadIo.count(st.lines))
        f.add("Commands", "Command lines", CadIo.count(st.cmdLines))
        f.add("Commands", "Lines with comments", CadIo.count(st.commentLines))
        val g0 = st.cmds["G0"] ?: 0; val g1 = st.cmds["G1"] ?: 0
        f.add("Commands", "G0 (rapid) / G1 (linear)", "$g0 / $g1")
        val gTotal = st.cmds.filterKeys { it[0] == 'G' }.values.sum()
        val mTotal = st.cmds.filterKeys { it[0] == 'M' }.values.sum()
        val tTotal = st.cmds.filterKeys { it[0] == 'T' }.values.sum()
        f.add("Commands", "G / M / T commands", "$gTotal / $mTotal / $tTotal")
        val top = st.cmds.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(10)
        f.add("Commands", "Top commands", top.joinToString(", ") { "${it.key} (${it.value})" })
        if (st.layerMarkers > 0) f.add("Layers", "Layer markers", CadIo.count(st.layerMarkers))
        f.add("Layers", "Layer count (comment)", st.layerCount)
        st.b.addFacts(f, "Toolpath", "Toolpath bounds (G0/G1)")
        if (partial) f.warn("File is larger than the read budget; command counts and bounds cover only the first ${Bytes.humanSize(head.size.toLong())}")
        return InspectionResult("gcode", "G-code", "G-code" + (slicer?.let { " from $it" } ?: "") + ", ${CadIo.count(st.cmdLines)} command lines", f.list, f.warnings)
    }
}
