package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Portable Game Notation chess files. */
object GamePgnInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("pgn")
    private const val READ = 4 * 1024 * 1024
    private val TAG = Regex("""^\[\s*([A-Za-z0-9_]+)\s+"((?:[^"\\]|\\.)*)"\s*]\s*$""")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private class Game(val tags: LinkedHashMap<String, String> = LinkedHashMap())

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val data = io.read(0, READ.toLong())
        if (data.isEmpty()) return null
        val text = Fg.utf8(data)
        val games = ArrayList<Game>()
        var cur: Game? = null
        var lines = 0
        var gameCount = 0
        var sawMoves = false
        for (raw in text.lineSequence()) {
            if (++lines > 1_000_000) break
            val line = raw.trim()
            val m = TAG.matchEntire(line)
            if (m != null) {
                val name = m.groupValues[1]
                if (name == "Event" && (cur == null || sawMoves || cur.tags.containsKey("Event"))) {
                    cur = Game(); gameCount++
                    if (games.size < 2000) games.add(cur)
                    sawMoves = false
                } else if (cur == null) {
                    cur = Game(); gameCount++
                    if (games.size < 2000) games.add(cur)
                    sawMoves = false
                }
                if (cur.tags.size < 64) cur.tags.putIfAbsent(name, m.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\"))
            } else if (line.isNotEmpty() && !line.startsWith("%")) {
                if (cur != null) sawMoves = true
            }
        }
        if (gameCount == 0) return null
        if (games.none { it.tags.containsKey("White") || it.tags.containsKey("Event") }) return null
        val first = games[0].tags
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        facts.add(Fact("Games" + if (io.size > data.size) " (in first ${Bytes.humanSize(data.size.toLong())})" else "", gameCount.toString(), "File"))
        for (k in listOf("Event", "Site", "Date", "Round", "White", "Black", "Result", "WhiteElo", "BlackElo", "ECO", "Opening", "TimeControl", "Termination", "FEN", "Variant")) {
            first[k]?.let { facts.add(Fact(k, Fg.clean(it).take(200), "First game")) }
        }
        val results = java.util.TreeMap<String, Int>()
        for (g in games) { val r = g.tags["Result"] ?: "(missing)"; results[r] = (results[r] ?: 0) + 1 }
        facts.add(Fact("Results", results.entries.joinToString(", ") { "${it.key} x${it.value}" }, "File"))
        val listing = games.take(15).mapIndexed { i, g ->
            "${i + 1}. ${Fg.clean(g.tags["White"] ?: "?")} vs ${Fg.clean(g.tags["Black"] ?: "?")} (${g.tags["Result"] ?: "?"})"
        }
        if (listing.isNotEmpty()) facts.add(Fact("Games listed", listing.joinToString("; "), "File"))
        if (io.size > data.size) warnings.add("only the first ${Bytes.humanSize(data.size.toLong())} was examined; the game count is a lower bound")
        val bad = games.count { it.tags["Result"]?.let { r -> r !in setOf("1-0", "0-1", "1/2-1/2", "*") } == true }
        if (bad > 0) warnings.add("$bad game(s) have a non-standard Result tag")
        val title = if (first["White"] != null && first["Black"] != null && gameCount == 1)
            "${Fg.clean(first["White"]!!)} vs ${Fg.clean(first["Black"]!!)}" else fileName ?: "PGN"
        return InspectionResult("pgn", title, "PGN with $gameCount game(s)", facts, warnings, text.take(FormatInspector.MAX_TEXT_CHARS))
    }
}

/** Forsyth-Edwards Notation position files. */
object GameFenInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("fen")
    private const val MAX_LINES = 2000

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private class Parsed(val white: Int, val black: Int, val side: String, val castling: String, val ep: String,
                         val half: String?, val full: String?, val problems: List<String>)

    private fun parse(line: String): Parsed? {
        val f = line.trim().split(Regex("\\s+"))
        if (f.size != 4 && f.size != 6) return null
        val ranks = f[0].split('/')
        if (ranks.size != 8) return null
        var white = 0; var black = 0
        val problems = ArrayList<String>()
        var wk = 0; var bk = 0
        for ((ri, r) in ranks.withIndex()) {
            var n = 0
            for (c in r) {
                when {
                    c in '1'..'8' -> n += c - '0'
                    c in "pnbrqkPNBRQK" -> {
                        n++
                        if (c.isUpperCase()) white++ else black++
                        if (c == 'K') wk++
                        if (c == 'k') bk++
                        if ((c == 'p' || c == 'P') && (ri == 0 || ri == 7)) problems.add("pawn on the first or last rank")
                    }
                    else -> return null
                }
            }
            if (n != 8) return null
        }
        if (f[1] != "w" && f[1] != "b") return null
        if (!Regex("^(-|[KQkq]{1,4})$").matches(f[2])) return null
        if (!Regex("^(-|[a-h][36])$").matches(f[3])) return null
        if (f.size == 6) {
            if (f[4].toIntOrNull()?.let { it >= 0 } != true || f[5].toIntOrNull()?.let { it >= 1 } != true) return null
        }
        if (wk != 1 || bk != 1) problems.add("expected exactly one king per side (white $wk, black $bk)")
        if (white > 16 || black > 16) problems.add("more than 16 pieces for a side")
        return Parsed(white, black, if (f[1] == "w") "White" else "Black", f[2], f[3], f.getOrNull(4), f.getOrNull(5), problems.distinct())
    }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val data = io.read(0, 256 * 1024)
        if (data.isEmpty()) return null
        val lines = Fg.utf8(data).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_LINES).toList()
        if (lines.isEmpty()) return null
        val first = parse(lines[0]) ?: return null
        var valid = 0
        for (l in lines) if (parse(l) != null) valid++
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        facts.add(Fact("Positions", "${lines.size}" + if (valid != lines.size) " ($valid valid)" else "", "File"))
        facts.add(Fact("First position", lines[0].take(120), "First position"))
        facts.add(Fact("Side to move", first.side, "First position"))
        facts.add(Fact("Castling rights", first.castling, "First position"))
        facts.add(Fact("En passant target", first.ep, "First position"))
        first.half?.let { facts.add(Fact("Halfmove clock", it, "First position")) }
        first.full?.let { facts.add(Fact("Fullmove number", it, "First position")) }
        facts.add(Fact("Pieces", "white ${first.white}, black ${first.black}", "First position"))
        if (valid != lines.size) warnings.add("${lines.size - valid} line(s) are not valid FEN")
        warnings.addAll(first.problems)
        if (first.half == null) warnings.add("clock fields are missing (4-field FEN)")
        return InspectionResult("fen", fileName ?: "FEN", "FEN position, ${first.side} to move", facts, warnings, null)
    }
}
