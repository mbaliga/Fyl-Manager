package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

private val U = DocumentInspectorsUtil

/** Text extraction shared by the XML-based document inspectors. */
internal object DocumentInspectorsXmlText {
    /** WordprocessingML / DrawingML / SpreadsheetML style: text in [textLocal] elements, paragraphs end at [paraLocal]. */
    fun runs(xml: String, textLocal: String, paraLocal: String, cap: Int): String {
        val sb = StringBuilder()
        var i = 0
        while (i < xml.length && sb.length < cap) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            if (lt + 1 >= xml.length) break
            val c = xml[lt + 1]
            if (c == '?' || c == '!') { val g = xml.indexOf('>', lt); if (g < 0) break; i = g + 1; continue }
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break
            if (c == '/') {
                var e = lt + 2
                while (e < gt && !xml[e].isWhitespace()) e++
                if (U.localName(xml.substring(lt + 2, e)) == paraLocal) sb.append('\n')
                i = gt + 1
                continue
            }
            var j = lt + 1
            while (j < gt && !xml[j].isWhitespace() && xml[j] != '/') j++
            val name = U.localName(xml.substring(lt + 1, j))
            val selfClose = xml[gt - 1] == '/'
            if (name == textLocal && !selfClose) {
                val close = xml.indexOf('<', gt + 1)
                if (close < 0) break
                sb.append(U.decodeEntities(xml.substring(gt + 1, close)))
                i = close
                continue
            }
            if (name == "tab") sb.append('\t') else if (name == "br" || name == "cr") sb.append('\n')
            i = gt + 1
        }
        return sb.toString()
    }

    /** ODF: strip tags inside office:body; paragraphs/headings end a line. */
    fun odf(xml: String, cap: Int): String {
        val b = xml.indexOf("<office:body")
        var i = if (b >= 0) b else 0
        val sb = StringBuilder()
        while (i < xml.length && sb.length < cap) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) { sb.append(U.decodeEntities(xml.substring(i))); break }
            if (lt > i) sb.append(U.decodeEntities(xml.substring(i, lt)))
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break
            val tag = xml.substring(lt + 1, gt)
            when {
                tag.startsWith("/text:p") || tag.startsWith("/text:h") -> sb.append('\n')
                tag.startsWith("text:s") && (tag.length == 6 || tag[6] == ' ' || tag[6] == '/') -> {
                    val n = U.attr(tag, "c")?.toIntOrNull()?.coerceIn(1, 100) ?: 1
                    repeat(n) { sb.append(' ') }
                }
                tag.startsWith("text:tab") -> sb.append('\t')
                tag.startsWith("text:line-break") -> sb.append('\n')
            }
            i = gt + 1
        }
        return sb.toString()
    }

    fun cap(s: String): String {
        val t = s.trim()
        return if (t.length <= FormatInspector.MAX_TEXT_CHARS) t else t.substring(0, FormatInspector.MAX_TEXT_CHARS)
    }
}

/** docx / xlsx / pptx (Office Open XML) inspector. */
object DocumentInspectorsOoxml : FormatInspector {
    override val formatIds: Set<String> = setOf("docx", "xlsx", "pptx")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { impl(source, fileName) } catch (t: Throwable) { null }

    private fun variant(ct: String): String? = when (ct) {
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml" -> "Document"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml" -> "Template (.dotx)"
        "application/vnd.ms-word.document.macroEnabled.main+xml" -> "Macro-enabled document (.docm)"
        "application/vnd.ms-word.template.macroEnabledTemplate.main+xml" -> "Macro-enabled template (.dotm)"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml" -> "Workbook"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template.main+xml" -> "Template (.xltx)"
        "application/vnd.ms-excel.sheet.macroEnabled.main+xml" -> "Macro-enabled workbook (.xlsm)"
        "application/vnd.ms-excel.template.macroEnabled.main+xml" -> "Macro-enabled template (.xltm)"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml" -> "Presentation"
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml" -> "Slide show (.ppsx)"
        "application/vnd.openxmlformats-officedocument.presentationml.template.main+xml" -> "Template (.potx)"
        "application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml" -> "Macro-enabled presentation (.pptm)"
        "application/vnd.ms-powerpoint.slideshow.macroEnabled.main+xml" -> "Macro-enabled slide show (.ppsm)"
        "application/vnd.ms-powerpoint.template.macroEnabled.main+xml" -> "Macro-enabled template (.potm)"
        else -> null
    }

    private fun encryptedPackage(source: ByteSource, fileName: String?): InspectionResult? {
        val ole = DocumentInspectorsOleFile.open(source) ?: return null
        val names = ole.entries.map { it.name }
        if ("EncryptedPackage" !in names) return null
        val ext = U.extensionOf(fileName)
        val id = if (ext in formatIds) ext!! else "docx"
        val out = DocumentInspectorsFacts()
        out.add("Structure", "Container", "OLE2 compound file holding an encrypted Office Open XML package")
        out.add("Structure", "Encrypted", "yes")
        out.add("Structure", "Streams", names.filter { it.isNotEmpty() }.take(20).joinToString(", ") { DocumentInspectorsOleFile.display(it) })
        out.warn("Password-protected: the document contents cannot be read without the password.")
        return InspectionResult(id, U.baseName(fileName) ?: "Encrypted Office document", "Encrypted Office Open XML document", out.facts, out.warnings)
    }

    private fun impl(source: ByteSource, fileName: String?): InspectionResult? {
        val magic = U.readSafe(source, 0, 8)
        if (DocumentInspectorsOleFile.isCfb(magic)) return encryptedPackage(source, fileName)
        val zip = DocumentInspectorsZipReader.open(source) ?: return null
        val ext = U.extensionOf(fileName)
        val kind = when {
            zip.has("word/document.xml") -> "docx"
            zip.has("xl/workbook.xml") -> "xlsx"
            zip.has("ppt/presentation.xml") -> "pptx"
            else -> return null
        }
        val out = DocumentInspectorsFacts()
        val gDoc = "Document"; val gStat = "Statistics"; val gApp = "Application"; val gStruct = "Structure"

        val mainPart = when (kind) { "docx" -> "/word/document.xml"; "xlsx" -> "/xl/workbook.xml"; else -> "/ppt/presentation.xml" }
        if (!zip.has(mainPart.substring(1))) out.warn("The main part ${mainPart.substring(1)} is missing; this does not look like a complete .$kind file.")

        // Content types -> variant
        var ctype: String? = null
        zip.readText("[Content_Types].xml", 256 * 1024)?.let { ct ->
            var from = 0
            var n = 0
            while (n++ < 5000) {
                val e = U.nextElem(ct, "Override", from) ?: break
                from = e.gt + 1
                val open = e.open(ct)
                if (U.attr(open, "PartName") == mainPart) { ctype = U.attr(open, "ContentType"); break }
            }
        }
        val ct0 = ctype
        if (ct0 != null) out.add(gStruct, "Variant", variant(ct0) ?: ct0)

        val core = zip.readText("docProps/core.xml", 128 * 1024)
        val app = zip.readText("docProps/app.xml", 128 * 1024)
        var title: String? = null
        if (core != null) {
            title = U.tag(core, "title")
            out.add(gDoc, "Title", title)
            out.add(gDoc, "Subject", U.tag(core, "subject"))
            out.add(gDoc, "Author", U.tag(core, "creator"))
            out.add(gDoc, "Keywords", U.tag(core, "keywords"))
            out.add(gDoc, "Description", U.tag(core, "description"))
            out.add(gDoc, "Last modified by", U.tag(core, "lastModifiedBy"))
            out.add(gDoc, "Revision", U.tag(core, "revision"))
            out.add(gDoc, "Created", U.tag(core, "created"))
            out.add(gDoc, "Modified", U.tag(core, "modified"))
            out.add(gDoc, "Category", U.tag(core, "category"))
        }
        var pagesFact: String? = null
        var wordsFact: String? = null
        var slidesFact: String? = null
        if (app != null) {
            out.add(gApp, "Application", U.tag(app, "Application"))
            out.add(gApp, "Application version", U.tag(app, "AppVersion"))
            out.add(gApp, "Company", U.tag(app, "Company"))
            out.add(gApp, "Manager", U.tag(app, "Manager"))
            pagesFact = U.tag(app, "Pages")
            wordsFact = U.tag(app, "Words")
            slidesFact = U.tag(app, "Slides")
            out.add(gStat, "Pages", pagesFact)
            out.add(gStat, "Words", wordsFact)
            out.add(gStat, "Characters", U.tag(app, "Characters"))
            out.add(gStat, "Characters (with spaces)", U.tag(app, "CharactersWithSpaces"))
            out.add(gStat, "Lines", U.tag(app, "Lines"))
            out.add(gStat, "Paragraphs", U.tag(app, "Paragraphs"))
            out.add(gStat, "Slides", slidesFact)
            out.add(gStat, "Notes", U.tag(app, "Notes"))
            out.add(gStat, "Hidden slides", U.tag(app, "HiddenSlides"))
            out.add(gStat, "Total editing time (minutes)", U.tag(app, "TotalTime"))
        }

        val names = zip.entries.map { it.name }
        val media = names.count { it.startsWith("word/media/") || it.startsWith("xl/media/") || it.startsWith("ppt/media/") }
        out.add(gStruct, "Parts in package", zip.entries.size.toLong())
        if (media > 0) out.add(gStruct, "Embedded media files", media.toLong())
        if (names.any { it.endsWith("vbaProject.bin") }) {
            out.add(gStruct, "Contains VBA macros", "yes")
            out.warn("The package contains a VBA project (macros).")
        }
        if (zip.directoryTruncated) out.warn("The ZIP central directory was truncated or very large; part counts may be incomplete.")

        var text: String? = null
        var summaryBits = ArrayList<String>()
        when (kind) {
            "docx" -> {
                if (names.contains("word/comments.xml")) out.add(gStruct, "Has comments", "yes")
                val c = names.count { it.startsWith("word/header") && it.endsWith(".xml") }
                val f = names.count { it.startsWith("word/footer") && it.endsWith(".xml") }
                if (c > 0) out.add(gStruct, "Headers", c.toLong())
                if (f > 0) out.add(gStruct, "Footers", f.toLong())
                zip.readText("word/document.xml", 1024 * 1024)?.let {
                    text = DocumentInspectorsXmlText.cap(DocumentInspectorsXmlText.runs(it, "t", "p", FormatInspector.MAX_TEXT_CHARS + 1024))
                }
                summaryBits.add("Word document")
                pagesFact?.let { summaryBits.add("$it pages") }
                wordsFact?.let { summaryBits.add("$it words") }
            }
            "xlsx" -> {
                val wb = zip.readText("xl/workbook.xml", 256 * 1024)
                val sheetNames = ArrayList<String>()
                if (wb != null) {
                    var from = 0
                    while (sheetNames.size < 500) {
                        val e = U.nextElem(wb, "sheet", from) ?: break
                        from = e.gt + 1
                        val open = e.open(wb)
                        val nm = U.attr(open, "name") ?: continue
                        val st = U.attr(open, "state")
                        sheetNames.add(if (st == "hidden") "$nm (hidden)" else if (st == "veryHidden") "$nm (very hidden)" else nm)
                    }
                    if (U.attr(U.nextElem(wb, "workbookPr", 0)?.open(wb) ?: "", "date1904") in listOf("1", "true"))
                        out.add(gStruct, "Date system", "1904")
                }
                out.add("Sheets", "Sheet count", sheetNames.size.toLong())
                for ((idx, nm) in sheetNames.withIndex()) out.add("Sheets", "Sheet ${idx + 1}", nm)
                val charts = names.count { it.startsWith("xl/charts/chart") && it.endsWith(".xml") }
                if (charts > 0) out.add(gStruct, "Charts", charts.toLong())
                val tables = names.count { it.startsWith("xl/tables/table") && it.endsWith(".xml") }
                if (tables > 0) out.add(gStruct, "Tables", tables.toLong())
                zip.readText("xl/sharedStrings.xml", 1024 * 1024)?.let { sst ->
                    val sb = StringBuilder()
                    var from = 0
                    var n = 0
                    while (sb.length < FormatInspector.MAX_TEXT_CHARS && n++ < 20000) {
                        val e = U.nextElem(sst, "si", from) ?: break
                        val end = sst.indexOf("</" + e.qname + ">", e.gt + 1)
                        if (e.selfClosing) { from = e.gt + 1; continue }
                        if (end < 0) break
                        val t = DocumentInspectorsXmlText.runs(sst.substring(e.gt + 1, end), "t", "si", 4096)
                        if (t.isNotBlank()) sb.append(t.trim()).append('\n')
                        from = end
                    }
                    if (sb.isNotEmpty()) text = DocumentInspectorsXmlText.cap(sb.toString())
                }
                summaryBits.add("Excel workbook")
                summaryBits.add("${sheetNames.size} sheets")
            }
            else -> {
                val slideEntries = names.filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
                out.add(gStat, "Slide parts", slideEntries.size.toLong())
                val notes = names.count { Regex("ppt/notesSlides/notesSlide\\d+\\.xml").matches(it) }
                if (notes > 0) out.add(gStruct, "Notes pages", notes.toLong())
                zip.readText("ppt/presentation.xml", 256 * 1024)?.let { p ->
                    val e = U.nextElem(p, "sldSz", 0)
                    if (e != null) {
                        val open = e.open(p)
                        val cx = U.attr(open, "cx")?.toLongOrNull()
                        val cy = U.attr(open, "cy")?.toLongOrNull()
                        if (cx != null && cy != null && cx > 0 && cy > 0) {
                            out.add(gStruct, "Slide size (EMU)", "$cx x $cy")
                            out.add(gStruct, "Slide size (inches)", "%.2f x %.2f".format(java.util.Locale.ROOT, cx / 914400.0, cy / 914400.0))
                        }
                    }
                }
                val ordered = slideEntries.sortedBy { it.filter { c -> c.isDigit() }.toLongOrNull() ?: 0L }.take(12)
                val sb = StringBuilder()
                for ((idx, nm) in ordered.withIndex()) {
                    if (sb.length >= FormatInspector.MAX_TEXT_CHARS) break
                    val x = zip.readText(nm, 512 * 1024) ?: continue
                    val t = DocumentInspectorsXmlText.runs(x, "t", "p", 8192).trim()
                    if (t.isNotEmpty()) sb.append("Slide ").append(idx + 1).append(":\n").append(t).append("\n\n")
                }
                if (sb.isNotEmpty()) text = DocumentInspectorsXmlText.cap(sb.toString())
                summaryBits.add("PowerPoint presentation")
                (slidesFact ?: slideEntries.size.toString()).let { summaryBits.add("$it slides") }
            }
        }
        if (names.none { it.startsWith("docProps/") }) out.warn("No docProps metadata parts found.")
        return InspectionResult(
            formatId = kind,
            title = title ?: U.baseName(fileName) ?: summaryBits.firstOrNull() ?: kind,
            summary = summaryBits.joinToString(" · "),
            facts = out.facts,
            warnings = out.warnings,
            text = text?.ifBlank { null },
        )
    }
}
