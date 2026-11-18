package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatCategory
import io.github.mbaliga.fylz.formats.FormatCategory.*
import io.github.mbaliga.fylz.formats.FormatSpec
import io.github.mbaliga.fylz.formats.Signature
import io.github.mbaliga.fylz.formats.sig
import io.github.mbaliga.fylz.formats.sigHex

/** Documents, office suites, ebooks, publishing, page description, help, outliners and notes. */
object DocumentsCatalog {
    private val OLE: Signature = sigHex(0, "D0 CF 11 E0 A1 B1 1A E1")
    private val ZIP: Signature = sigHex(0, "50 4B 03 04")

    /** ZIP whose first entry is an uncompressed "mimetype" file (ODF, EPUB, IDML). */
    private fun zipMime(mime: String): Signature = ZIP + sig(30, "mimetype") + sig(38, mime)

    private fun odf(
        id: String, label: String, cat: FormatCategory, mime: String, internal: String = mime,
        exts: List<String> = listOf(id), desc: String = "",
    ) = FormatSpec(id, label, cat, exts, listOf(mime), listOf(zipMime(internal)), description = desc)

    private fun zipSpec(
        id: String, label: String, cat: FormatCategory, exts: List<String> = listOf(id),
        mimes: List<String> = emptyList(), desc: String = "",
    ) = FormatSpec(id, label, cat, exts, mimes, listOf(ZIP), description = desc)

    private fun oleSpec(
        id: String, label: String, cat: FormatCategory, exts: List<String>,
        mimes: List<String> = emptyList(), desc: String = "",
    ) = FormatSpec(id, label, cat, exts, mimes, listOf(OLE), description = desc)

    private fun ext(
        id: String, label: String, cat: FormatCategory, exts: List<String> = listOf(id),
        mimes: List<String> = emptyList(), desc: String = "", text: Boolean = false,
    ) = FormatSpec(id, label, cat, exts, mimes, emptyList(), text = text, description = desc)

    private const val OO = "application/vnd.oasis.opendocument."
    private const val MSO = "application/vnd.openxmlformats-officedocument."
    private const val SUN = "application/vnd.sun.xml."

    val specs: List<FormatSpec> = listOf(
        // ---- PDF and page description ----
        FormatSpec("pdf", "PDF document", DOCUMENT, listOf("pdf"), listOf("application/pdf"),
            listOf(sig(0, "%PDF-"))),
        FormatSpec("fdf", "PDF Forms Data Format", DOCUMENT, listOf("fdf"),
            listOf("application/vnd.fdf"), listOf(sig(0, "%FDF-")), description = "Form field data exchanged with PDF forms."),
        ext("xfdf", "XML Forms Data Format", DOCUMENT, mimes = listOf("application/vnd.adobe.xfdf"), text = true),
        ext("xdp", "XML Data Package (XFA)", DOCUMENT, mimes = listOf("application/vnd.adobe.xdp+xml"), text = true),
        FormatSpec("ps", "PostScript", DOCUMENT, listOf("ps"), listOf("application/postscript"),
            listOf(sig(0, "%!PS")), text = true, description = "Page description language program."),
        FormatSpec("eps", "Encapsulated PostScript", VECTOR, listOf("eps", "epsf", "epsi", "epi", "ept"),
            listOf("application/eps", "application/x-eps", "image/x-eps"),
            listOf(sig(0, "%!PS-Adobe-3.0 EPSF-3.0"), sig(0, "%!PS-Adobe-2.0 EPSF-2.0"), sigHex(0, "C5 D0 D3 C6")),
            description = "PostScript fragment for embedding; may carry a binary DOS EPS header."),
        zipSpec("xps", "XPS document", DOCUMENT, mimes = listOf("application/vnd.ms-xpsdocument"),
            desc = "XML Paper Specification, a ZIP package."),
        zipSpec("oxps", "OpenXPS document", DOCUMENT, mimes = listOf("application/oxps")),
        zipSpec("ofd", "OFD fixed-layout document", DOCUMENT, mimes = listOf("application/ofd"),
            desc = "Chinese national fixed-layout standard (GB/T 33190), a ZIP package."),
        FormatSpec("djvu", "DjVu document", DOCUMENT, listOf("djvu", "djv"),
            listOf("image/vnd.djvu", "image/x-djvu"),
            listOf(sigHex(0, "41 54 26 54 46 4F 52 4D ?? ?? ?? ?? 44 4A 56 55"),
                sigHex(0, "41 54 26 54 46 4F 52 4D ?? ?? ?? ?? 44 4A 56 4D"))),
        FormatSpec("dvi", "TeX DVI", DOCUMENT, listOf("dvi"), listOf("application/x-dvi"),
            listOf(sigHex(0, "F7 02 01 83 92 C0 1C 3B 00 00")), description = "Device-independent output of TeX."),
        ext("xdv", "Extended DVI (XeTeX)", DOCUMENT),

        // ---- Microsoft legacy (OLE2) ----
        oleSpec("doc", "Word 97-2003 document", DOCUMENT, listOf("doc", "dot"),
            listOf("application/msword"), "Compound File Binary container."),
        oleSpec("xls", "Excel 97-2003 workbook", SPREADSHEET, listOf("xls", "xlt", "xla", "xlw"),
            listOf("application/vnd.ms-excel")),
        oleSpec("ppt", "PowerPoint 97-2003 presentation", PRESENTATION, listOf("ppt", "pot", "pps", "ppa"),
            listOf("application/vnd.ms-powerpoint")),
        oleSpec("pub", "Publisher document", PUBLISHING, listOf("pub"), listOf("application/vnd.ms-publisher")),
        oleSpec("vsd", "Visio drawing (legacy)", DOCUMENT, listOf("vsd", "vss", "vst"),
            listOf("application/vnd.visio")),
        oleSpec("mpp", "Microsoft Project plan", DOCUMENT, listOf("mpp", "mpt"),
            listOf("application/vnd.ms-project")),
        oleSpec("wps", "Microsoft Works word processor", DOCUMENT, listOf("wps"),
            listOf("application/vnd.ms-works")),
        FormatSpec("one", "OneNote notebook section", DOCUMENT, listOf("one"),
            listOf("application/onenote"),
            listOf(sigHex(0, "E4 52 5C 7B 8C D8 A7 4D AE B1 53 78 D0 29 96 D3")),
            description = "OneNote 2010+ revision-store file."),
        ext("onetoc2", "OneNote table of contents", DOCUMENT, mimes = listOf("application/onenote")),
        ext("onepkg", "OneNote package", DOCUMENT, mimes = listOf("application/onenote"), desc = "CAB archive of a notebook."),
        FormatSpec("wri", "Windows Write document", DOCUMENT, listOf("wri"), listOf("application/x-mswrite"),
            listOf(sigHex(0, "31 BE 00 00 00 AB"))),
        ext("wdb", "Microsoft Works database", DOCUMENT),
        ext("xlr", "Microsoft Works spreadsheet", SPREADSHEET),
        ext("mpx", "Microsoft Project exchange", DOCUMENT, text = true),
        ext("vdx", "Visio XML drawing", DOCUMENT, mimes = listOf("application/vnd.ms-visio.drawing"), text = true),

        // ---- Microsoft OOXML ----
        zipSpec("docx", "Word document", DOCUMENT, mimes = listOf(MSO + "wordprocessingml.document"),
            desc = "Office Open XML word processing."),
        zipSpec("docm", "Word macro-enabled document", DOCUMENT, mimes = listOf("application/vnd.ms-word.document.macroEnabled.12")),
        zipSpec("dotx", "Word template", DOCUMENT, mimes = listOf(MSO + "wordprocessingml.template")),
        zipSpec("dotm", "Word macro-enabled template", DOCUMENT, mimes = listOf("application/vnd.ms-word.template.macroEnabled.12")),
        zipSpec("xlsx", "Excel workbook", SPREADSHEET, mimes = listOf(MSO + "spreadsheetml.sheet")),
        zipSpec("xlsm", "Excel macro-enabled workbook", SPREADSHEET, mimes = listOf("application/vnd.ms-excel.sheet.macroEnabled.12")),
        zipSpec("xlsb", "Excel binary workbook", SPREADSHEET, mimes = listOf("application/vnd.ms-excel.sheet.binary.macroEnabled.12")),
        zipSpec("xltx", "Excel template", SPREADSHEET, mimes = listOf(MSO + "spreadsheetml.template")),
        zipSpec("xltm", "Excel macro-enabled template", SPREADSHEET, mimes = listOf("application/vnd.ms-excel.template.macroEnabled.12")),
        zipSpec("xlam", "Excel add-in", SPREADSHEET, mimes = listOf("application/vnd.ms-excel.addin.macroEnabled.12")),
        zipSpec("pptx", "PowerPoint presentation", PRESENTATION, mimes = listOf(MSO + "presentationml.presentation")),
        zipSpec("pptm", "PowerPoint macro-enabled presentation", PRESENTATION, mimes = listOf("application/vnd.ms-powerpoint.presentation.macroEnabled.12")),
        zipSpec("potx", "PowerPoint template", PRESENTATION, mimes = listOf(MSO + "presentationml.template")),
        zipSpec("potm", "PowerPoint macro-enabled template", PRESENTATION, mimes = listOf("application/vnd.ms-powerpoint.template.macroEnabled.12")),
        zipSpec("ppsx", "PowerPoint slide show", PRESENTATION, mimes = listOf(MSO + "presentationml.slideshow")),
        zipSpec("ppsm", "PowerPoint macro-enabled slide show", PRESENTATION, mimes = listOf("application/vnd.ms-powerpoint.slideshow.macroEnabled.12")),
        zipSpec("ppam", "PowerPoint add-in", PRESENTATION, mimes = listOf("application/vnd.ms-powerpoint.addin.macroEnabled.12")),
        zipSpec("sldx", "PowerPoint slide", PRESENTATION, mimes = listOf(MSO + "presentationml.slide")),
        zipSpec("thmx", "Office theme", DOCUMENT, mimes = listOf("application/vnd.ms-officetheme")),
        zipSpec("vsdx", "Visio drawing", DOCUMENT, mimes = listOf("application/vnd.ms-visio.drawing")),
        zipSpec("vsdm", "Visio macro-enabled drawing", DOCUMENT, mimes = listOf("application/vnd.ms-visio.drawing.macroEnabled.12")),
        zipSpec("vssx", "Visio stencil", DOCUMENT, mimes = listOf("application/vnd.ms-visio.stencil")),
        zipSpec("vstx", "Visio template", DOCUMENT, mimes = listOf("application/vnd.ms-visio.template")),
        zipSpec("mshc", "Microsoft Help Viewer content", DOCUMENT, mimes = listOf("application/vnd.ms-help")),

        // ---- OpenDocument ----
        odf("odt", "OpenDocument text", DOCUMENT, OO + "text"),
        odf("ott", "OpenDocument text template", DOCUMENT, OO + "text-template"),
        odf("odm", "OpenDocument master document", DOCUMENT, OO + "text-master"),
        odf("oth", "OpenDocument HTML template", DOCUMENT, OO + "text-web"),
        odf("ods", "OpenDocument spreadsheet", SPREADSHEET, OO + "spreadsheet"),
        odf("ots", "OpenDocument spreadsheet template", SPREADSHEET, OO + "spreadsheet-template"),
        odf("odp", "OpenDocument presentation", PRESENTATION, OO + "presentation"),
        odf("otp", "OpenDocument presentation template", PRESENTATION, OO + "presentation-template"),
        odf("odg", "OpenDocument drawing", VECTOR, OO + "graphics"),
        odf("otg", "OpenDocument drawing template", VECTOR, OO + "graphics-template"),
        odf("odc", "OpenDocument chart", DOCUMENT, OO + "chart"),
        odf("odf", "OpenDocument formula", DOCUMENT, OO + "formula"),
        odf("odi", "OpenDocument image", IMAGE, OO + "image"),
        odf("odb", "OpenDocument database", DATABASE, OO + "base", internal = SUN + "base"),
        FormatSpec("fodt", "Flat OpenDocument text", DOCUMENT, listOf("fodt"), listOf(OO + "text"), text = true),
        FormatSpec("fods", "Flat OpenDocument spreadsheet", SPREADSHEET, listOf("fods"), listOf(OO + "spreadsheet"), text = true),
        FormatSpec("fodp", "Flat OpenDocument presentation", PRESENTATION, listOf("fodp"), listOf(OO + "presentation"), text = true),
        FormatSpec("fodg", "Flat OpenDocument drawing", VECTOR, listOf("fodg"), listOf(OO + "graphics"), text = true),

        // ---- StarOffice / OpenOffice 1.x ----
        odf("sxw", "OpenOffice.org 1.x Writer", DOCUMENT, SUN + "writer"),
        odf("sxc", "OpenOffice.org 1.x Calc", SPREADSHEET, SUN + "calc"),
        odf("sxi", "OpenOffice.org 1.x Impress", PRESENTATION, SUN + "impress"),
        odf("sxd", "OpenOffice.org 1.x Draw", VECTOR, SUN + "draw"),
        odf("sxm", "OpenOffice.org 1.x Math", DOCUMENT, SUN + "math"),
        ext("stw", "OpenOffice.org 1.x Writer template", DOCUMENT, mimes = listOf(SUN + "writer.template")),
        ext("stc", "OpenOffice.org 1.x Calc template", SPREADSHEET, mimes = listOf(SUN + "calc.template")),
        ext("sti", "OpenOffice.org 1.x Impress template", PRESENTATION, mimes = listOf(SUN + "impress.template")),
        oleSpec("sdw", "StarWriter document", DOCUMENT, listOf("sdw", "sgl", "vor"), desc = "StarOffice binary format."),
        oleSpec("sdc", "StarCalc spreadsheet", SPREADSHEET, listOf("sdc")),
        oleSpec("sdd", "StarImpress presentation", PRESENTATION, listOf("sdd", "sdp")),
        oleSpec("sda", "StarDraw drawing", VECTOR, listOf("sda")),

        // ---- Apple iWork ----
        zipSpec("pages", "Apple Pages document", DOCUMENT, listOf("pages", "pages-tef"), listOf("application/vnd.apple.pages")),
        zipSpec("numbers", "Apple Numbers spreadsheet", SPREADSHEET, listOf("numbers", "nmbtemplate"), listOf("application/vnd.apple.numbers")),
        zipSpec("key", "Apple Keynote presentation", PRESENTATION, listOf("key", "kth"), listOf("application/vnd.apple.keynote")),

        // ---- WordPerfect, Lotus, Corel, other office suites ----
        FormatSpec("wpd", "WordPerfect document", DOCUMENT, listOf("wpd", "wpt", "wpp", "wp", "wp4", "wp5", "wp6"),
            listOf("application/vnd.wordperfect", "application/wordperfect"),
            listOf(sigHex(0, "FF 57 50 43"))),
        ext("qpw", "Quattro Pro spreadsheet", SPREADSHEET, listOf("qpw", "wb1", "wb2", "wb3", "wq1", "wq2")),
        ext("shw", "Corel Presentations slide show", PRESENTATION, listOf("shw")),
        FormatSpec("wk1", "Lotus 1-2-3 worksheet (WK1)", SPREADSHEET, listOf("wk1"),
            listOf("application/vnd.lotus-1-2-3"), listOf(sigHex(0, "00 00 02 00 06 04 06 00"))),
        FormatSpec("wk3", "Lotus 1-2-3 worksheet (WK3)", SPREADSHEET, listOf("wk3"),
            listOf("application/vnd.lotus-1-2-3"), listOf(sigHex(0, "00 00 1A 00 00 10 04 00"))),
        FormatSpec("wk4", "Lotus 1-2-3 worksheet (WK4)", SPREADSHEET, listOf("wk4"),
            listOf("application/vnd.lotus-1-2-3"), listOf(sigHex(0, "00 00 1A 00 02 10 04 00"))),
        ext("wks", "Lotus 1-2-3 / Works spreadsheet", SPREADSHEET, mimes = listOf("application/vnd.lotus-1-2-3")),
        ext("123", "Lotus 1-2-3 SmartSuite spreadsheet", SPREADSHEET, listOf("123"), listOf("application/vnd.lotus-1-2-3")),
        ext("lwp", "Lotus Word Pro document", DOCUMENT, mimes = listOf("application/vnd.lotus-wordpro")),
        ext("sam", "Ami Pro document", DOCUMENT),
        ext("prz", "Lotus Freelance Graphics presentation", PRESENTATION, mimes = listOf("application/vnd.lotus-freelance")),
        ext("abw", "AbiWord document", DOCUMENT, mimes = listOf("application/x-abiword"), text = true, desc = "XML."),
        ext("zabw", "AbiWord compressed document", DOCUMENT, mimes = listOf("application/x-abiword-compressed")),
        zipSpec("kwd", "Calligra Words / KWord document", DOCUMENT, mimes = listOf("application/x-kword")),
        oleSpec("hwp", "Hancom Hangul document", DOCUMENT, listOf("hwp"), listOf("application/x-hwp", "application/haansofthwp")),
        zipSpec("hwpx", "Hancom Hangul XML document", DOCUMENT, mimes = listOf("application/hwp+zip")),
        ext("jtd", "Ichitaro document", DOCUMENT, listOf("jtd", "jtt")),
        ext("gdoc", "Google Docs shortcut", DOCUMENT, mimes = listOf("application/vnd.google-apps.document"), desc = "JSON pointer to an online document.", text = true),
        ext("gsheet", "Google Sheets shortcut", SPREADSHEET, mimes = listOf("application/vnd.google-apps.spreadsheet"), text = true),
        ext("gslides", "Google Slides shortcut", PRESENTATION, mimes = listOf("application/vnd.google-apps.presentation"), text = true),
        FormatSpec("slk", "SYLK spreadsheet", SPREADSHEET, listOf("slk", "sylk"), listOf("application/x-sylk"),
            listOf(sig(0, "ID;P")), text = true, description = "Symbolic Link text interchange format."),
        FormatSpec("rtf", "Rich Text Format", DOCUMENT, listOf("rtf"), listOf("application/rtf", "text/rtf"),
            listOf(sig(0, "{\\rtf")), text = true),

        // ---- Ebooks ----
        FormatSpec("epub", "EPUB ebook", EBOOK, listOf("epub"), listOf("application/epub+zip"),
            listOf(zipMime("application/epub+zip"))),
        FormatSpec("mobi", "Mobipocket ebook", EBOOK, listOf("mobi", "prc"),
            listOf("application/x-mobipocket-ebook"), listOf(sig(60, "BOOKMOBI")), description = "Palm database with a BOOKMOBI header."),
        FormatSpec("azw", "Kindle AZW ebook", EBOOK, listOf("azw"), listOf("application/vnd.amazon.ebook"),
            listOf(sig(60, "BOOKMOBI"))),
        FormatSpec("azw3", "Kindle KF8 ebook", EBOOK, listOf("azw3", "kf8"), listOf("application/vnd.amazon.mobi8-ebook"),
            listOf(sig(60, "BOOKMOBI"))),
        ext("kfx", "Kindle KFX ebook", EBOOK),
        ext("fb2", "FictionBook 2", EBOOK, listOf("fb2", "fb2.zip", "fbz"), listOf("application/x-fictionbook+xml"), text = true),
        FormatSpec("lit", "Microsoft Reader ebook", EBOOK, listOf("lit"), listOf("application/x-ms-reader"),
            listOf(sig(0, "ITOLITLS"))),
        FormatSpec("pdb", "Palm database", EBOOK, listOf("pdb"), listOf("application/vnd.palm", "application/x-pilot-pdb"),
            listOf(sig(60, "TEXtREAd")), description = "PalmDOC and other Palm databases."),
        FormatSpec("lrf", "Sony Reader LRF ebook", EBOOK, listOf("lrf"), listOf("application/x-sony-bbeb"),
            listOf(sigHex(0, "4C 00 52 00 46 00 00 00"))),
        FormatSpec("umd", "Universal Mobile Document ebook", EBOOK, listOf("umd"),
            signatures = listOf(sigHex(0, "89 9B 9A DE"))),
        zipSpec("ibooks", "Apple iBooks Author book", EBOOK, mimes = listOf("application/x-ibooks+zip")),
        ext("acsm", "Adobe Content Server Message", EBOOK, mimes = listOf("application/vnd.adobe.adept+xml"), text = true),
        ext("opf", "Open Packaging Format", EBOOK, mimes = listOf("application/oebps-package+xml"), text = true),

        // ---- LaTeX / TeX / typesetting ----
        ext("ins", "LaTeX installer script", DOCUMENT, text = true),
        ext("aux", "LaTeX auxiliary file", DOCUMENT, text = true),
        ext("bbl", "BibTeX generated bibliography", DOCUMENT, text = true),
        FormatSpec("lyx", "LyX document", DOCUMENT, listOf("lyx"), listOf("application/x-lyx"),
            listOf(sig(0, "#LyX")), text = true),
        ext("rnw", "Sweave / knitr document", DOCUMENT, listOf("rnw", "snw"), text = true),
        ext("dita", "DITA topic", DOCUMENT, listOf("dita", "ditamap", "ditaval"), listOf("application/dita+xml"), text = true),

        // ---- Desktop publishing ----
        FormatSpec("indd", "Adobe InDesign document", PUBLISHING, listOf("indd", "indt", "indb", "indl"),
            listOf("application/x-indesign"),
            listOf(sigHex(0, "06 06 ED F5 D8 1D 46 E5 BD 31 EF E7 FE 74 B7 1D"))),
        FormatSpec("idml", "InDesign Markup Language", PUBLISHING, listOf("idml"),
            listOf("application/vnd.adobe.indesign-idml-package"),
            listOf(zipMime("application/vnd.adobe.indesign-idml-package"))),
        ext("icml", "InCopy Markup Language", PUBLISHING, text = true),
        ext("qxd", "QuarkXPress document", PUBLISHING, listOf("qxd", "qxp", "qxt"), listOf("application/vnd.quark.quarkxpress")),
        ext("sla", "Scribus document", PUBLISHING, listOf("sla"), listOf("application/vnd.scribus"), text = true, desc = "XML; may be gzipped as sla.gz."),
        ext("pmd", "Adobe PageMaker document", PUBLISHING, listOf("pmd", "pm6", "pm5", "pm4", "p65", "pm3")),
        FormatSpec("fm", "Adobe FrameMaker document", PUBLISHING, listOf("fm", "book"), listOf("application/vnd.framemaker"),
            listOf(sig(0, "<MakerFile")), text = true),
        FormatSpec("mif", "FrameMaker Interchange Format", PUBLISHING, listOf("mif"), listOf("application/vnd.mif"),
            listOf(sig(0, "<MIFFile")), text = true),

        // ---- Help files ----
        FormatSpec("chm", "Compiled HTML Help", DOCUMENT, listOf("chm", "chi"), listOf("application/vnd.ms-htmlhelp"),
            listOf(sig(0, "ITSF"))),
        FormatSpec("hlp", "Windows Help", DOCUMENT, listOf("hlp"), listOf("application/winhlp"),
            listOf(sigHex(0, "3F 5F 03 00"))),
        ext("hhp", "HTML Help project", DOCUMENT, listOf("hhp", "hhc", "hhk"), text = true),

        // ---- Outliners, mind maps, diagrams ----
        ext("taskpaper", "TaskPaper outline", DOCUMENT, text = true),
        ext("leo", "Leo outline", DOCUMENT, text = true),
        ext("mm-freemind", "FreeMind mind map", DOCUMENT, listOf("mm"), listOf("application/x-freemind"), text = true),
        zipSpec("xmind", "XMind mind map", DOCUMENT, mimes = listOf("application/vnd.xmind.workbook")),
        zipSpec("mmap", "MindManager map", DOCUMENT, mimes = listOf("application/vnd.mindjet.mindmanager")),
        ext("smmx", "SimpleMind mind map", DOCUMENT),
        ext("itmz", "iThoughts mind map", DOCUMENT),
        ext("mindnode", "MindNode mind map", DOCUMENT),
        ext("mup", "MindMup mind map", DOCUMENT, text = true, desc = "JSON."),
        ext("vym", "View Your Mind map", DOCUMENT),

        // ---- Notes ----
        ext("enex", "Evernote export", DOCUMENT, mimes = listOf("application/x-evernote-export"), text = true),
        ext("jex", "Joplin export archive", DOCUMENT, desc = "TAR archive of notes."),
        ext("textpack", "TextBundle compressed", DOCUMENT, mimes = listOf("application/x-textbundle")),
        ext("nsx", "Synology Note Station export", DOCUMENT),
        ext("sdocx", "Samsung Notes document", DOCUMENT),
        ext("xoj", "Xournal notes", DOCUMENT, desc = "Gzip-compressed XML."),
        ext("xopp", "Xournal++ notes", DOCUMENT, desc = "Gzip-compressed XML."),

        // ---- Additional markup, ebook and office formats ----
        ext("dbk", "DocBook document", DOCUMENT, listOf("dbk", "docbook"), listOf("application/docbook+xml"), text = true),
        ext("htmlz", "HTML zip ebook", EBOOK, desc = "ZIP archive containing HTML."),
        ext("txtz", "Text zip ebook", EBOOK, desc = "ZIP archive containing text."),
        ext("snb", "Shanda Bambook ebook", EBOOK),
        ext("tcr", "TCR compressed ebook", EBOOK),
        ext("gnumeric", "Gnumeric spreadsheet", SPREADSHEET, mimes = listOf("application/x-gnumeric"), desc = "Gzip-compressed XML."),
        ext("oxt", "OpenOffice extension", DOCUMENT, mimes = listOf("application/vnd.openofficeorg.extension"), desc = "ZIP package."),
        ext("sxg", "OpenOffice.org 1.x master document", DOCUMENT, mimes = listOf(SUN + "writer.global")),
    )
}
