package io.github.mbaliga.fylz.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Preview wave 1's pure readers (desktop-class audit, "Quick Look credibility markers"):
 * word-processing text, EML, EPUB, and ICS/VCF cards. All of them share three rules:
 *
 * - **Bounded everything.** Bytes read, entries scanned, and characters kept are capped, and
 *   hitting a cap is REPORTED by the caller, never silently applied (the spreadsheet
 *   preview's precedent).
 * - **Text out, not fidelity.** These answer "what does this file say", the Quick Look
 *   question, not "what does it look like". No layout is promised and none is faked.
 * - **Pure.** Streams in, values out, no Android — so every parser is a plain JUnit test away
 *   from being trusted.
 */
object StructuredDocumentReaders {

    const val MAX_TEXT_CHARS = 200_000
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    const val MAX_ARCHIVE_ENTRIES = 2_000

    data class ExtractedText(val text: String, val truncated: Boolean)

    // ── Word processing: DOCX / ODT / RTF ────────────────────────────────────────────

    /**
     * DOCX: `word/document.xml`, paragraphs on `</w:p>`. ODT: `content.xml`, paragraphs on
     * `</text:p>` and `</text:h>`. Both are "unzip one entry, strip tags" — the same zip
     * plumbing the workbook reader trusts.
     */
    fun readWordProcessing(extension: String, open: () -> InputStream): ExtractedText =
        when (extension.lowercase(Locale.ROOT)) {
            "docx", "docm", "dotx", "dot" -> readZipEntryText(open, setOf("word/document.xml"), "</w:p>")
            "odt", "ott" -> readZipEntryText(open, setOf("content.xml"), "</text:p>", "</text:h>")
            "rtf" -> readRtf(open)
            else -> error("Not a word-processing format Fylz can read: .$extension")
        }

    private fun readZipEntryText(
        open: () -> InputStream,
        entryNames: Set<String>,
        vararg paragraphMarks: String,
    ): ExtractedText {
        ZipInputStream(open().buffered()).use { zip ->
            var scanned = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++scanned > MAX_ARCHIVE_ENTRIES) break
                if (!entry.isDirectory && entry.name in entryNames) {
                    val xml = zip.readBounded(MAX_ENTRY_BYTES)
                    var marked = xml.content
                    paragraphMarks.forEach { mark -> marked = marked.replace(mark, "$mark\n") }
                    val text = stripTags(marked)
                    return bounded(text, upstreamTruncated = xml.truncated)
                }
                zip.closeEntry()
            }
        }
        error("This document holds no readable text part.")
    }

    /** RTF: control words dropped, groups unwrapped, `\par` becomes a line break. */
    private fun readRtf(open: () -> InputStream): ExtractedText {
        val raw = open().buffered().readBounded(MAX_ENTRY_BYTES)
        val text = raw.content
            .replace(Regex("""\\par[d]?\b"""), "\n")
            // Unicode escapes: \uNNNN? carries a decimal code point and one fallback char.
            .replace(Regex("""\\u(-?\d+)\??.?""")) { match ->
                match.groupValues[1].toIntOrNull()?.let { code ->
                    if (code >= 0) code.toChar().toString() else ""
                }.orEmpty()
            }
            .replace(Regex("""\\'[0-9a-fA-F]{2}"""), "")
            .replace(Regex("""\\[a-zA-Z]+-?\d* ?"""), "")
            .replace("{", "").replace("}", "")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return bounded(text, upstreamTruncated = raw.truncated)
    }

    // ── EML ──────────────────────────────────────────────────────────────────────────

    data class EmailPreview(
        val from: String?,
        val to: String?,
        val subject: String?,
        val date: String?,
        val bodyText: String,
        val bodyTruncated: Boolean,
        val attachmentNames: List<String>,
    )

    /**
     * RFC-822-shaped mail: unfolded headers, then the first `text/plain` part's decoded text
     * (quoted-printable and base64 handled; HTML-only mail falls back to stripped HTML).
     * Attachments are NAMED, never opened — the preview says what the mail carries, it does
     * not detonate it.
     */
    fun readEml(open: () -> InputStream): EmailPreview {
        val raw = open().buffered().readBounded(MAX_ENTRY_BYTES)
        val normalized = raw.content.replace("\r\n", "\n")
        val headerEnd = normalized.indexOf("\n\n").takeIf { it >= 0 } ?: normalized.length
        val headers = unfoldHeaders(normalized.substring(0, headerEnd))
        val bodyRegion = normalized.substring((headerEnd + 2).coerceAtMost(normalized.length))

        val contentType = headers["content-type"].orEmpty()
        val boundary = Regex("""boundary="?([^";\n]+)"?""", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)

        val attachments = mutableListOf<String>()
        var plain: String? = null
        var html: String? = null

        if (boundary != null) {
            bodyRegion.split("--$boundary").drop(1).forEach { part ->
                if (part.startsWith("--")) return@forEach
                val partHeaderEnd = part.indexOf("\n\n").takeIf { it >= 0 } ?: return@forEach
                val partHeaders = unfoldHeaders(part.substring(0, partHeaderEnd).trim('\n'))
                val partBody = part.substring(partHeaderEnd + 2)
                val disposition = partHeaders["content-disposition"].orEmpty()
                val partType = partHeaders["content-type"].orEmpty().lowercase(Locale.ROOT)
                val fileName = Regex("""(?:file)?name="?([^";\n]+)"?""", RegexOption.IGNORE_CASE)
                    .find(disposition.ifEmpty { partHeaders["content-type"].orEmpty() })
                    ?.groupValues?.get(1)
                when {
                    disposition.contains("attachment", ignoreCase = true) || (fileName != null && !partType.startsWith("text/")) ->
                        attachments += fileName ?: "unnamed attachment"
                    partType.startsWith("text/plain") && plain == null ->
                        plain = decodeTransfer(partBody, partHeaders["content-transfer-encoding"])
                    partType.startsWith("text/html") && html == null ->
                        html = decodeTransfer(partBody, partHeaders["content-transfer-encoding"])
                }
            }
        } else {
            val decoded = decodeTransfer(bodyRegion, headers["content-transfer-encoding"])
            if (contentType.contains("text/html", ignoreCase = true)) html = decoded else plain = decoded
        }

        val body = plain ?: html?.let(::stripTags) ?: ""
        val boundedBody = bounded(body.trim(), upstreamTruncated = raw.truncated)
        return EmailPreview(
            from = headers["from"],
            to = headers["to"],
            subject = headers["subject"],
            date = headers["date"],
            bodyText = boundedBody.text,
            bodyTruncated = boundedBody.truncated,
            attachmentNames = attachments,
        )
    }

    private fun unfoldHeaders(block: String): Map<String, String> {
        val unfolded = block.replace(Regex("\n[ \t]+"), " ")
        return unfolded.lines().mapNotNull { line ->
            val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(0, colon).trim().lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }.toMap()
    }

    private fun decodeTransfer(body: String, encoding: String?): String =
        when (encoding?.trim()?.lowercase(Locale.ROOT)) {
            "base64" -> runCatching {
                String(java.util.Base64.getMimeDecoder().decode(body.trim()))
            }.getOrDefault(body)
            "quoted-printable" -> body
                .replace(Regex("=\n"), "")
                .replace(Regex("=([0-9A-Fa-f]{2})")) { m ->
                    m.groupValues[1].toInt(16).toChar().toString()
                }
            else -> body
        }

    // ── EPUB ─────────────────────────────────────────────────────────────────────────

    /**
     * EPUB is zip + XHTML. One pass collects the container's XHTML chapters in archive order
     * (spine-order resolution needs random access a stream cannot give; archive order matches
     * it in practice for the overwhelming majority of real books, and the header says what was
     * read rather than implying completeness).
     */
    fun readEpub(open: () -> InputStream, maxChapters: Int = 6): ExtractedText {
        val chapters = mutableListOf<String>()
        var truncatedByEntry = false
        ZipInputStream(open().buffered()).use { zip ->
            var scanned = 0
            while (chapters.size < maxChapters) {
                val entry = zip.nextEntry ?: break
                if (++scanned > MAX_ARCHIVE_ENTRIES) break
                val name = entry.name.lowercase(Locale.ROOT)
                if (!entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))) {
                    val read = zip.readBounded(MAX_ENTRY_BYTES)
                    truncatedByEntry = truncatedByEntry || read.truncated
                    val text = stripTags(
                        read.content
                            .replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
                            .replace(Regex("(?i)</(p|div|h[1-6]|li|br)>"), "\n"),
                    ).trim()
                    if (text.isNotBlank()) chapters += text
                }
                zip.closeEntry()
            }
        }
        if (chapters.isEmpty()) error("This book holds no readable chapters.")
        return bounded(chapters.joinToString("\n\n⁂\n\n"), upstreamTruncated = truncatedByEntry)
    }

    // ── ICS / VCF cards ──────────────────────────────────────────────────────────────

    data class CardField(val label: String, val value: String)
    data class StructuredCard(val title: String, val fields: List<CardField>, val more: Int)

    /** VEVENT/VTODO cards out of a calendar file; property parameters are dropped, values kept. */
    fun readCalendar(open: () -> InputStream, maxCards: Int = 5): List<StructuredCard> {
        val lines = unfoldIcsLines(open)
        val cards = mutableListOf<StructuredCard>()
        var inEvent = false
        var current = mutableMapOf<String, String>()
        var total = 0
        for (line in lines) {
            when {
                line.startsWith("BEGIN:VEVENT") || line.startsWith("BEGIN:VTODO") -> {
                    inEvent = true; current = mutableMapOf()
                }
                line.startsWith("END:VEVENT") || line.startsWith("END:VTODO") -> {
                    if (inEvent) {
                        total += 1
                        if (cards.size < maxCards) cards += eventCard(current)
                    }
                    inEvent = false
                }
                inEvent -> {
                    val (key, value) = icsKeyValue(line) ?: continue
                    current.putIfAbsent(key, value)
                }
            }
        }
        return cards.map { it.copy(more = (total - cards.size).coerceAtLeast(0)) }
    }

    /** FN/ORG/TEL/EMAIL/ADR cards out of a vCard file. */
    fun readContacts(open: () -> InputStream, maxCards: Int = 8): List<StructuredCard> {
        val lines = unfoldIcsLines(open)
        val cards = mutableListOf<StructuredCard>()
        var inCard = false
        var current = mutableListOf<Pair<String, String>>()
        var total = 0
        for (line in lines) {
            when {
                line.startsWith("BEGIN:VCARD") -> { inCard = true; current = mutableListOf() }
                line.startsWith("END:VCARD") -> {
                    if (inCard) {
                        total += 1
                        if (cards.size < maxCards) cards += contactCard(current)
                    }
                    inCard = false
                }
                inCard -> icsKeyValue(line)?.let(current::add)
            }
        }
        return cards.map { it.copy(more = (total - cards.size).coerceAtLeast(0)) }
    }

    private fun eventCard(properties: Map<String, String>): StructuredCard {
        val fields = buildList {
            properties["DTSTART"]?.let { add(CardField("Starts", formatIcsTime(it))) }
            properties["DTEND"]?.let { add(CardField("Ends", formatIcsTime(it))) }
            properties["LOCATION"]?.let { add(CardField("Where", unescapeIcs(it))) }
            properties["ORGANIZER"]?.let { add(CardField("Organizer", it.removePrefix("mailto:"))) }
            properties["DESCRIPTION"]?.let { add(CardField("Notes", unescapeIcs(it).take(400))) }
        }
        return StructuredCard(unescapeIcs(properties["SUMMARY"] ?: "Untitled event"), fields, more = 0)
    }

    private fun contactCard(properties: List<Pair<String, String>>): StructuredCard {
        val byKey = properties.groupBy({ it.first }, { it.second })
        val fields = buildList {
            byKey["ORG"]?.firstOrNull()?.let { add(CardField("Organization", unescapeIcs(it))) }
            byKey["TITLE"]?.firstOrNull()?.let { add(CardField("Title", unescapeIcs(it))) }
            byKey["TEL"]?.forEach { add(CardField("Phone", it)) }
            byKey["EMAIL"]?.forEach { add(CardField("Email", it)) }
            byKey["ADR"]?.firstOrNull()?.let {
                add(CardField("Address", unescapeIcs(it).split(";").filter(String::isNotBlank).joinToString(", ")))
            }
        }
        val name = byKey["FN"]?.firstOrNull()
            ?: byKey["N"]?.firstOrNull()?.split(";")?.filter(String::isNotBlank)?.reversed()?.joinToString(" ")
            ?: "Unnamed contact"
        return StructuredCard(unescapeIcs(name), fields, more = 0)
    }

    private fun unfoldIcsLines(open: () -> InputStream): List<String> {
        val raw = open().buffered().readBounded(MAX_ENTRY_BYTES).content.replace("\r\n", "\n")
        return raw.replace(Regex("\n[ \t]"), "").lines()
    }

    private fun icsKeyValue(line: String): Pair<String, String>? {
        val colon = line.indexOf(':').takeIf { it > 0 } ?: return null
        val key = line.substring(0, colon).substringBefore(';').uppercase(Locale.ROOT)
        return key to line.substring(colon + 1).trim()
    }

    private fun formatIcsTime(value: String): String {
        // 20260823T161500Z / 20260823 — rendered readably without pretending to know the zone.
        val m = Regex("""(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})\d{2}(Z?))?""").find(value) ?: return value
        val (y, mo, d, h, min, z) = m.destructured
        return if (h.isEmpty()) "$y-$mo-$d" else "$y-$mo-$d $h:$min${if (z == "Z") " UTC" else ""}"
    }

    private fun unescapeIcs(value: String): String =
        value.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    // ── Shared plumbing ──────────────────────────────────────────────────────────────

    private data class BoundedRead(val content: String, val truncated: Boolean)

    private fun InputStream.readBounded(maxBytes: Long): BoundedRead {
        val buffer = ByteArray(64 * 1024)
        val out = StringBuilder()
        var total = 0L
        while (total < maxBytes) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total).toInt())
            if (read < 0) return BoundedRead(out.toString(), truncated = false)
            out.append(String(buffer, 0, read, Charsets.UTF_8))
            total += read
        }
        return BoundedRead(out.toString(), truncated = read() >= 0)
    }

    private fun stripTags(markup: String): String = markup
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
        .lines().joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun bounded(text: String, upstreamTruncated: Boolean): ExtractedText =
        if (text.length > MAX_TEXT_CHARS) {
            ExtractedText(text.take(MAX_TEXT_CHARS), truncated = true)
        } else {
            ExtractedText(text, truncated = upstreamTruncated)
        }

    /** Test seam: parse from a string without a file. */
    fun openerOf(content: String): () -> InputStream = { ByteArrayInputStream(content.toByteArray()) }
}
