package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

private val U = DocumentInspectorsUtil

/** odt / ods / odp (OpenDocument) inspector. */
object DocumentInspectorsOdf : FormatInspector {
    override val formatIds: Set<String> = setOf("odt", "ods", "odp")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { impl(source, fileName) } catch (t: Throwable) { null }

    private fun impl(source: ByteSource, fileName: String?): InspectionResult? {
        val zip = DocumentInspectorsZipReader.open(source) ?: return null
        val mime = zip.read(zip.find("mimetype") ?: return null, 256)?.text()?.trim()
        val ext = U.extensionOf(fileName)
        val base = mime?.removePrefix("application/vnd.oasis.opendocument.")
        val kind = when {
            base != null && (base == "text" || base == "text-template") -> "odt"
            base != null && (base == "spreadsheet" || base == "spreadsheet-template") -> "ods"
            base != null && (base == "presentation" || base == "presentation-template") -> "odp"
            else -> return null
        }
        val out = DocumentInspectorsFacts()
        val gDoc = "Document"; val gStat = "Statistics"; val gApp = "Application"; val gStruct = "Structure"
        out.add(gStruct, "MIME type", mime)
        if (zip.directoryTruncated) out.warn("The ZIP central directory was truncated or very large; counts may be incomplete.")

        val manifest = zip.readText("META-INF/manifest.xml", 256 * 1024)
        if (manifest != null && manifest.contains("encryption-data")) {
            out.add(gStruct, "Encrypted", "yes")
            out.warn("Some parts are encrypted: text and statistics may be unavailable without the password.")
        }

        var title: String? = null
        val meta = zip.readText("meta.xml", 256 * 1024)
        if (meta != null) {
            title = U.tag(meta, "title")
            out.add(gDoc, "Title", title)
            out.add(gDoc, "Subject", U.tag(meta, "subject"))
            out.add(gDoc, "Description", U.tag(meta, "description"))
            val kw = U.tagAll(meta, "keyword", 50)
            if (kw.isNotEmpty()) out.add(gDoc, "Keywords", kw.joinToString(", "))
            out.add(gDoc, "Author (initial creator)", U.tag(meta, "initial-creator"))
            out.add(gDoc, "Last modified by", U.tag(meta, "creator"))
            out.add(gDoc, "Created", U.tag(meta, "creation-date"))
            out.add(gDoc, "Modified", U.tag(meta, "date"))
            out.add(gDoc, "Editing cycles", U.tag(meta, "editing-cycles"))
            out.add(gDoc, "Editing duration", U.tag(meta, "editing-duration"))
            out.add(gDoc, "Language", U.tag(meta, "language"))
            out.add(gApp, "Generator", U.tag(meta, "generator"))
            val st = U.nextElem(meta, "document-statistic", 0)
            if (st != null) {
                val open = st.open(meta)
                for ((key, label) in listOf(
                    "page-count" to "Pages", "paragraph-count" to "Paragraphs", "word-count" to "Words",
                    "character-count" to "Characters", "non-whitespace-character-count" to "Non-whitespace characters",
                    "table-count" to "Tables", "image-count" to "Images", "object-count" to "Objects",
                    "cell-count" to "Cells", "row-count" to "Rows",
                )) out.add(gStat, label, U.attr(open, key))
            } else {
                out.warn("meta.xml has no document-statistic element.")
            }
        } else {
            out.warn("No meta.xml found: no document metadata.")
        }

        val names = zip.entries.map { it.name }
        out.add(gStruct, "Parts in package", zip.entries.size.toLong())
        val pics = names.count { it.startsWith("Pictures/") && !it.endsWith("/") }
        if (pics > 0) out.add(gStruct, "Embedded pictures", pics.toLong())
        if (names.any { it.startsWith("Basic/") || it.startsWith("Scripts/") }) out.add(gStruct, "Contains macros/scripts", "yes")

        var text: String? = null
        val bits = ArrayList<String>()
        val content = zip.find("content.xml")?.let { zip.read(it, 1024 * 1024) }
        val cx = content?.text()
        when (kind) {
            "odt" -> {
                bits.add("OpenDocument text")
                if (cx != null) text = DocumentInspectorsXmlText.cap(DocumentInspectorsXmlText.odf(cx, FormatInspector.MAX_TEXT_CHARS + 1024))
            }
            "ods" -> {
                bits.add("OpenDocument spreadsheet")
                if (cx != null) {
                    val sheets = ArrayList<String>()
                    var from = 0
                    while (sheets.size < 500) {
                        val e = U.nextElem(cx, "table", from) ?: break
                        from = e.gt + 1
                        val nm = U.attr(e.open(cx), "name") ?: continue
                        sheets.add(nm)
                    }
                    val partial = content.truncated
                    out.add("Sheets", if (partial) "Sheet count (at least)" else "Sheet count", sheets.size.toLong())
                    for ((i, n) in sheets.withIndex()) out.add("Sheets", "Sheet ${i + 1}", n)
                    if (partial) out.warn("content.xml is large; only its first 1 MiB was scanned for sheets.")
                    bits.add("${sheets.size}${if (partial) "+" else ""} sheets")
                }
            }
            else -> {
                bits.add("OpenDocument presentation")
                if (cx != null) {
                    var n = 0
                    var from = 0
                    val titles = ArrayList<String>()
                    while (n < 5000) {
                        val e = U.nextElem(cx, "page", from) ?: break
                        from = e.gt + 1
                        n++
                        U.attr(e.open(cx), "name")?.let { if (titles.size < 50) titles.add(it) }
                    }
                    val partial = content.truncated
                    out.add(gStat, if (partial) "Slides (at least)" else "Slides", n.toLong())
                    for ((i, t) in titles.withIndex()) out.add("Slides", "Slide ${i + 1}", t)
                    if (partial) out.warn("content.xml is large; only its first 1 MiB was scanned for slides.")
                    bits.add("$n${if (partial) "+" else ""} slides")
                    text = DocumentInspectorsXmlText.cap(DocumentInspectorsXmlText.odf(cx, FormatInspector.MAX_TEXT_CHARS + 1024)).ifBlank { null }
                }
            }
        }
        return InspectionResult(
            formatId = kind,
            title = title ?: U.baseName(fileName) ?: bits.first(),
            summary = bits.joinToString(" · "),
            facts = out.facts,
            warnings = out.warnings,
            text = text?.ifBlank { null },
        )
    }
}
