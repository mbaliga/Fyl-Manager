package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

private val U = DocumentInspectorsUtil

/** EPUB inspector: container.xml -> OPF package metadata, manifest and spine counts. */
object DocumentInspectorsEpub : FormatInspector {
    override val formatIds: Set<String> = setOf("epub")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { impl(source, fileName) } catch (t: Throwable) { null }

    private fun impl(source: ByteSource, fileName: String?): InspectionResult? {
        val zip = DocumentInspectorsZipReader.open(source) ?: return null
        val mime = zip.find("mimetype")?.let { zip.read(it, 256) }?.text()?.trim()
        val container = zip.readText("META-INF/container.xml", 128 * 1024)
        if (mime != "application/epub+zip" && container == null) return null
        val out = DocumentInspectorsFacts()
        val gBook = "Book"; val gPkg = "Package"; val gStruct = "Structure"
        if (mime != "application/epub+zip") out.warn("The mimetype entry is missing or not 'application/epub+zip'.")
        else out.add(gStruct, "MIME type", mime)
        if (zip.directoryTruncated) out.warn("The ZIP central directory was truncated or very large; counts may be incomplete.")

        var opfPath: String? = null
        if (container != null) {
            var from = 0
            var n = 0
            while (n++ < 50) {
                val e = U.nextElem(container, "rootfile", from) ?: break
                from = e.gt + 1
                val p = U.attr(e.open(container), "full-path")
                if (p != null) { opfPath = p; break }
            }
        } else out.warn("META-INF/container.xml not found.")
        out.add(gStruct, "Package document", opfPath)

        var title: String? = null
        val bits = ArrayList<String>()
        bits.add("EPUB")
        val opfEntry = opfPath?.let { zip.find(it) }
        val opf = opfEntry?.let { zip.read(it, 1024 * 1024) }
        if (opf != null) {
            val x = opf.text()
            val pkg = U.nextElem(x, "package", 0)
            val ver = pkg?.let { U.attr(it.open(x), "version") }
            if (ver != null) { out.add(gPkg, "EPUB version", ver); bits[0] = "EPUB $ver" }
            title = U.tag(x, "title")
            out.add(gBook, "Title", title)
            val creators = U.tagAll(x, "creator", 20)
            if (creators.isNotEmpty()) out.add(gBook, "Author", creators.joinToString("; "))
            val contributors = U.tagAll(x, "contributor", 10)
            if (contributors.isNotEmpty()) out.add(gBook, "Contributors", contributors.joinToString("; "))
            out.add(gBook, "Language", U.tagAll(x, "language", 5).joinToString(", "))
            out.add(gBook, "Publisher", U.tag(x, "publisher"))
            out.add(gBook, "Published", U.tag(x, "date"))
            out.add(gBook, "Identifier", U.tag(x, "identifier"))
            out.add(gBook, "Description", U.tag(x, "description"))
            val subjects = U.tagAll(x, "subject", 30)
            if (subjects.isNotEmpty()) out.add(gBook, "Subjects", subjects.joinToString(", "))
            out.add(gBook, "Rights", U.tag(x, "rights"))
            // EPUB 3 modification date: <meta property="dcterms:modified">...</meta>
            var from = 0
            var n = 0
            while (n++ < 500) {
                val e = U.nextElem(x, "meta", from) ?: break
                from = e.gt + 1
                if (U.attr(e.open(x), "property") == "dcterms:modified" && !e.selfClosing) {
                    out.add(gPkg, "Modified", U.tag(x, "meta", e.start)); break
                }
            }
            val items = U.count(x, "item", 100_000)
            val spine = U.count(x, "itemref", 100_000)
            out.add(gPkg, "Manifest items", items.toLong())
            out.add(gPkg, "Spine items (reading order)", spine.toLong())
            if (opf.truncated) out.warn("The package document is very large; counts cover only its first 1 MiB.")
        } else if (opfPath != null) {
            out.warn("The package document '$opfPath' could not be read.")
        }
        val names = zip.entries.map { it.name }
        val content = names.count { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }
        out.add(gStruct, "Content documents (xhtml/html)", content.toLong())
        val images = names.count { val l = it.lowercase(); l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".gif") || l.endsWith(".svg") || l.endsWith(".webp") }
        if (images > 0) out.add(gStruct, "Images", images.toLong())
        if (names.contains("META-INF/encryption.xml")) {
            out.add(gStruct, "Encryption descriptor", "META-INF/encryption.xml present")
            out.warn("encryption.xml is present: some resources may be DRM-protected or font-obfuscated.")
        }
        if (names.contains("META-INF/rights.xml")) out.add(gStruct, "Rights descriptor", "META-INF/rights.xml present")
        return InspectionResult(
            formatId = "epub",
            title = title ?: U.baseName(fileName) ?: "EPUB book",
            summary = bits.joinToString(" · "),
            facts = out.facts,
            warnings = out.warnings,
        )
    }
}
