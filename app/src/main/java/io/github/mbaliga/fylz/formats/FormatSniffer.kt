package io.github.mbaliga.fylz.formats

/** How a [SniffResult] was decided, strongest first. */
enum class Confidence { MAGIC, MAGIC_CONTAINER, EXTENSION, MIME, TEXT_HEURISTIC, NONE }

class SniffResult(
    val spec: FormatSpec?,
    val confidence: Confidence,
    /** Other specs that matched equally well (ambiguous extension, overlapping magic). */
    val alternatives: List<FormatSpec> = emptyList(),
    /** True when a signature matched but the file name's extension names a different format. */
    val extensionMismatch: Boolean = false,
    /** True for a file with no recognised format whose bytes look like text. */
    val looksLikeText: Boolean = false,
) {
    val id: String? get() = spec?.id
}

/**
 * Decides what a file is from, in order of trust: its bytes, its name, its MIME type.
 *
 * A signature beats an extension (the extension is a claim; the bytes are evidence), the most
 * specific signature wins, and a generic container (zip, ole2, riff...) never beats a specific
 * format that the name says it is (docx, doc, wav...) — see [FormatSpec.containerId].
 */
class FormatSniffer(private val specs: List<FormatSpec>) {

    private val byId: Map<String, FormatSpec> = specs.associateBy { it.id }
    private val byExtension: Map<String, List<FormatSpec>> = buildMap<String, MutableList<FormatSpec>> {
        for (s in specs) for (e in s.extensions) getOrPut(e) { mutableListOf() }.add(s)
    }
    private val byMime: Map<String, List<FormatSpec>> = buildMap<String, MutableList<FormatSpec>> {
        for (s in specs) for (m in s.mimeTypes) getOrPut(m.lowercase()) { mutableListOf() }.add(s)
    }

    fun byId(id: String): FormatSpec? = byId[id]
    fun forExtension(ext: String): List<FormatSpec> = byExtension[ext.lowercase().trimStart('.')].orEmpty()
    val all: List<FormatSpec> get() = specs

    fun sniff(header: ByteArray, fileName: String? = null, mimeType: String? = null): SniffResult {
        val extSpecs = extensionSpecs(fileName)

        // 1. Signatures: collect every spec with a matching signature, scored by specificity.
        val magicHits = specs.mapNotNull { spec ->
            val best = spec.signatures.filter { it.matches(header) }.maxOfOrNull { it.specificity }
            if (best == null) null else spec to best
        }
        if (magicHits.isNotEmpty()) {
            val top = magicHits.maxOf { it.second }
            val leaders = magicHits.filter { it.second == top }.map { it.first }

            // A container hit yields to an extension-named specialisation of that container.
            for (ext in extSpecs) {
                val container = ext.containerId
                if (container != null && magicHits.any { it.first.id == container }) {
                    return SniffResult(ext, Confidence.MAGIC_CONTAINER, alternatives = leaders.filter { it !== ext })
                }
            }
            // Otherwise prefer a leader the extension also names, else the first leader.
            val agreed = leaders.firstOrNull { it in extSpecs }
            val chosen = agreed ?: leaders.first()
            return SniffResult(
                spec = chosen,
                confidence = Confidence.MAGIC,
                alternatives = leaders.filter { it !== chosen },
                extensionMismatch = nameSuffixes(fileName).let { suffixes ->
                    suffixes.isNotEmpty() &&
                        suffixes.none { it in chosen.extensions } &&
                        extSpecs.none { it.containerId == chosen.id }
                },
            )
        }

        // 2. Extension.
        if (extSpecs.isNotEmpty()) {
            return SniffResult(extSpecs.first(), Confidence.EXTENSION, alternatives = extSpecs.drop(1))
        }

        // 3. MIME type.
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim()
        if (mime != null) {
            val hits = byMime[mime].orEmpty()
            if (hits.isNotEmpty()) return SniffResult(hits.first(), Confidence.MIME, alternatives = hits.drop(1))
        }

        // 4. Nothing known: is it at least text?
        return if (looksLikeText(header)) {
            SniffResult(null, Confidence.TEXT_HEURISTIC, looksLikeText = true)
        } else {
            SniffResult(null, Confidence.NONE)
        }
    }

    /** Every dotted suffix of the name, longest first: "a.tar.gz" -> ["tar.gz", "gz"]. Empty if none. */
    private fun nameSuffixes(fileName: String?): List<String> {
        if (fileName.isNullOrBlank()) return emptyList()
        val parts = fileName.substringAfterLast('/').lowercase().split('.')
        return (1 until parts.size).map { parts.drop(it).joinToString(".") }.filter { it.isNotEmpty() }
    }

    private fun extensionSpecs(fileName: String?): List<FormatSpec> {
        if (fileName.isNullOrBlank()) return emptyList()
        val name = fileName.substringAfterLast('/').lowercase()
        // Longest matching extension wins, so "tar.gz" beats "gz".
        val parts = name.split('.')
        for (start in 1 until parts.size) {
            val candidate = parts.drop(start).joinToString(".")
            val hits = byExtension[candidate]
            if (!hits.isNullOrEmpty()) return hits
        }
        // Extension-less well-known names (Makefile, Dockerfile): the whole name as an "extension".
        return byExtension[name].orEmpty()
    }

    companion object {
        /** Signatures must end within this many bytes; callers hand the sniffer at least this much. */
        const val MAX_HEADER_BYTES = 131_072

        /** True when [bytes] is plausibly text: valid-ish UTF-8/ASCII/UTF-16 with few control bytes. */
        fun looksLikeText(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return true
            // UTF-16 / UTF-8 byte-order marks.
            if (bytes.size >= 2) {
                val b0 = bytes[0].toInt() and 0xFF; val b1 = bytes[1].toInt() and 0xFF
                if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return true
            }
            if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF &&
                (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF
            ) return true
            val n = minOf(bytes.size, 8192)
            var control = 0
            for (i in 0 until n) {
                val b = bytes[i].toInt() and 0xFF
                if (b == 0) return false
                if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C && b != 0x1B) control++
            }
            return control * 100 < n * 2   // under 2% control bytes
        }
    }
}
