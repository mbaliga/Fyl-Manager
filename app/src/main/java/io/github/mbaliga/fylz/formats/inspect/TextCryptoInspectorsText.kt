package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Plain text: encoding, line endings, counts, indentation, shebang and modeline. */
object TextFileInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("txt")
    private const val READ_LIMIT = 1 shl 20

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source) }

    private fun startsWith(d: ByteArray, vararg v: Int): Boolean {
        if (d.size < v.size) return false
        for (i in v.indices) if ((d[i].toInt() and 0xFF) != v[i]) return false
        return true
    }

    private fun utf8Valid(d: ByteArray, truncated: Boolean): Boolean {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val r = dec.decode(ByteBuffer.wrap(d), CharBuffer.allocate(d.size + 1), !truncated)
        return !r.isError
    }

    private val shells = setOf("sh", "bash", "zsh", "dash", "ksh", "ash", "csh", "tcsh", "fish")
    private fun languageForInterpreter(name: String): String? {
        val base = name.trimEnd { it.isDigit() || it == '.' }
        return when {
            base in shells -> if (base == "csh" || base == "tcsh") "C shell script" else if (base == "fish") "fish script" else "Shell script ($base)"
            base == "python" -> "Python"
            base == "perl" -> "Perl"
            base == "ruby" -> "Ruby"
            base == "node" || base == "nodejs" -> "JavaScript (Node.js)"
            base == "php" -> "PHP"
            base == "lua" || base == "luajit" -> "Lua"
            base == "awk" || base == "gawk" || base == "mawk" -> "AWK"
            base == "pwsh" || base == "powershell" -> "PowerShell"
            base == "Rscript" -> "R"
            base == "tclsh" || base == "wish" -> "Tcl"
            base == "julia" -> "Julia"
            base == "groovy" -> "Groovy"
            base == "swift" -> "Swift"
            else -> null
        }
    }

    private fun shebang(first: String): Pair<String, String>? {
        if (!first.startsWith("#!")) return null
        val toks = first.substring(2).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.isEmpty()) return null
        var interp = toks[0]
        val args = toks.drop(1)
        if (interp.substringAfterLast('/') == "env") {
            val real = args.firstOrNull { !it.startsWith("-") && !it.contains('=') } ?: return interp to first
            interp = real
        }
        return interp to first.trim()
    }

    private val vimRe = Regex("(?:^|\\s)(?:vim?|ex):.*?\\b(?:ft|filetype)=([A-Za-z0-9_+.-]+)")
    private val emacsRe = Regex("-\\*-(.*?)-\\*-")
    private val emacsMode = Regex("mode:\\s*([A-Za-z0-9_+.-]+)", RegexOption.IGNORE_CASE)

    private fun modeline(lines: List<String>): String? {
        for (l in lines.take(2)) {
            val m = emacsRe.find(l) ?: continue
            val inner = m.groupValues[1].trim()
            val mm = emacsMode.find(inner)
            if (mm != null) return mm.groupValues[1] + " (Emacs mode line)"
            if (inner.isNotEmpty() && !inner.contains(':') && !inner.contains(';')) return "$inner (Emacs mode line)"
        }
        val cand = lines.take(5) + lines.takeLast(5)
        for (l in cand) {
            val m = vimRe.find(l) ?: continue
            return m.groupValues[1] + " (Vim modeline)"
        }
        return null
    }

    private fun run(source: ByteSource): InspectionResult? {
        val data = TC.readUpTo(source, READ_LIMIT)
        val truncated = TC.truncated(source, data)
        val warnings = ArrayList<String>()
        val facts = ArrayList<Fact>()
        if (data.isEmpty()) {
            return InspectionResult("txt", "Empty text file", "0 bytes", listOf(Fact("Size", "0 bytes", "File")))
        }
        var bom = 0
        var encName: String
        var cs: Charset? = null
        var bomName: String? = null
        when {
            startsWith(data, 0xEF, 0xBB, 0xBF) -> { bom = 3; encName = "UTF-8"; cs = Charsets.UTF_8; bomName = "UTF-8 BOM" }
            startsWith(data, 0xFF, 0xFE, 0, 0) -> { bom = 4; encName = "UTF-32LE"; cs = try { Charset.forName("UTF-32LE") } catch (e: Throwable) { null }; bomName = "UTF-32LE BOM" }
            startsWith(data, 0, 0, 0xFE, 0xFF) -> { bom = 4; encName = "UTF-32BE"; cs = try { Charset.forName("UTF-32BE") } catch (e: Throwable) { null }; bomName = "UTF-32BE BOM" }
            startsWith(data, 0xFF, 0xFE) -> { bom = 2; encName = "UTF-16LE"; cs = Charsets.UTF_16LE; bomName = "UTF-16LE BOM" }
            startsWith(data, 0xFE, 0xFF) -> { bom = 2; encName = "UTF-16BE"; cs = Charsets.UTF_16BE; bomName = "UTF-16BE BOM" }
            else -> {
                var even = 0; var odd = 0; var nul = 0
                for (i in data.indices) if (data[i].toInt() == 0) { nul++; if (i % 2 == 0) even++ else odd++ }
                val half = data.size / 2
                if (nul > 0 && odd > even * 4 && odd >= maxOf(1, half / 4)) {
                    encName = "UTF-16LE (detected, no BOM)"; cs = Charsets.UTF_16LE
                } else if (nul > 0 && even > odd * 4 && even >= maxOf(1, half / 4)) {
                    encName = "UTF-16BE (detected, no BOM)"; cs = Charsets.UTF_16BE
                } else if (nul > 0) {
                    encName = "unknown (contains NUL bytes)"; cs = Charsets.ISO_8859_1
                    warnings.add("Contains $nul NUL bytes in a pattern that is not UTF-16; this is probably a binary file")
                } else if (data.all { it.toInt() >= 0 }) {
                    encName = "ASCII"; cs = Charsets.UTF_8
                } else if (utf8Valid(data, truncated)) {
                    encName = "UTF-8"; cs = Charsets.UTF_8
                } else {
                    encName = "ISO-8859-1 / Windows-1252 (assumed; not valid UTF-8)"; cs = Charsets.ISO_8859_1
                }
            }
        }
        val text: String
        if (cs == null) {
            warnings.add("This JVM cannot decode $encName; counts are not available")
            facts.add(Fact("Encoding", encName, "Encoding"))
            return InspectionResult("txt", "Text file ($encName)", Bytes.humanSize(source.size), facts, warnings)
        } else {
            var len = data.size - bom
            if (cs == Charsets.UTF_16LE || cs == Charsets.UTF_16BE) len = len and -2
            else if (encName.startsWith("UTF-32")) len = len and -4
            var s = String(data, bom, maxOf(0, len), cs)
            if (truncated && s.endsWith("�") && cs == Charsets.UTF_8) s = s.dropLast(1)
            text = s
        }

        // Single pass over the decoded text.
        var lf = 0; var crlf = 0; var cr = 0
        var lines = 0; var longest = 0; var nonBlank = 0
        var tabIndented = 0; var spaceIndented = 0
        var lineStart = 0
        fun endLine(end: Int) {
            lines++
            if (end - lineStart > longest) longest = end - lineStart
            var j = lineStart
            while (j < end && (text[j] == ' ' || text[j] == '\t')) j++
            if (j < end) {
                nonBlank++
                if (text[lineStart] == '\t') tabIndented++
                else if (j - lineStart >= 2 && text[lineStart] == ' ') spaceIndented++
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r') {
                if (i + 1 < text.length && text[i + 1] == '\n') { crlf++; endLine(i); i += 2; lineStart = i; continue }
                cr++; endLine(i); lineStart = i + 1
            } else if (c == '\n') { lf++; endLine(i); lineStart = i + 1 }
            i++
        }
        if (lineStart < text.length) endLine(text.length)
        var words = 0
        var inWord = false
        for (ch in text) {
            if (Character.isWhitespace(ch) || ch == ' ') inWord = false
            else if (!inWord) { inWord = true; words++ }
        }
        val chars = text.codePointCount(0, text.length)
        var control = 0
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v < 0x20 && v != 9 && v != 10 && v != 13 && v != 12 && v != 27 && v != 0) control++
        }
        if (control * 10 > data.size) warnings.add("Over 10% of bytes are control characters; this may not be a text file")

        val kinds = listOf("LF" to lf, "CRLF" to crlf, "CR" to cr).filter { it.second > 0 }
        val eol = when {
            kinds.isEmpty() -> "none (single line)"
            kinds.size == 1 -> kinds[0].first
            else -> "Mixed (" + kinds.joinToString(", ") { "${it.first} ${it.second}" } + ")"
        }
        val indent = when {
            tabIndented == 0 && spaceIndented == 0 -> "none detected"
            tabIndented > 0 && spaceIndented == 0 -> "tabs ($tabIndented lines)"
            spaceIndented > 0 && tabIndented == 0 -> "spaces ($spaceIndented lines)"
            else -> "mixed (tabs $tabIndented lines, spaces $spaceIndented lines)"
        }

        facts.add(Fact("Encoding", encName, "Encoding"))
        if (bomName != null) facts.add(Fact("Byte order mark", bomName, "Encoding"))
        if (truncated) facts.add(Fact("Counts cover", "first ${Bytes.humanSize(data.size.toLong())} of ${Bytes.humanSize(source.size)}", "Counts"))
        facts.add(Fact("Lines", lines.toString(), "Counts"))
        facts.add(Fact("Non-blank lines", nonBlank.toString(), "Counts"))
        facts.add(Fact("Words", words.toString(), "Counts"))
        facts.add(Fact("Characters", chars.toString(), "Counts"))
        facts.add(Fact("Longest line", "$longest characters", "Counts"))
        facts.add(Fact("Line endings", eol, "Layout"))
        facts.add(Fact("Ends with newline", if (text.endsWith("\n") || text.endsWith("\r")) "yes" else "no", "Layout"))
        facts.add(Fact("Indentation", indent, "Layout"))

        val allLines = text.take(200_000).split('\n').map { it.trimEnd('\r') }
        var language: String? = null
        val sb = shebang(allLines.first())
        if (sb != null) {
            val interp = sb.first
            facts.add(Fact("Shebang", TC.cap(sb.second, 200), "Script"))
            facts.add(Fact("Interpreter", interp, "Script"))
            val lang = languageForInterpreter(interp.substringAfterLast('/'))
            if (lang != null) { language = lang; facts.add(Fact("Likely language", "$lang (from shebang)", "Script")) }
        }
        val ml = modeline(allLines)
        if (ml != null) {
            facts.add(Fact("Modeline", ml, "Script"))
            if (language == null) facts.add(Fact("Likely language", ml, "Script"))
        }
        return InspectionResult(
            "txt", "Text file ($encName)",
            "$lines line${if (lines == 1) "" else "s"}, $words word${if (words == 1) "" else "s"}",
            facts, warnings, text.take(FormatInspector.MAX_TEXT_CHARS),
        )
    }
}
