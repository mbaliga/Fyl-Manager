package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*

/** Obsolete office suites, Lotus/WordStar/WordPerfect, and the FoxPro/dBase/Paradox/Access/Btrieve family. */
object Gap2x8Catalog {

    private fun f(
        id: String, label: String, cat: FormatCategory, ext: List<String> = listOf(id),
        mime: List<String> = emptyList(), sigs: List<Signature> = emptyList(),
        container: String? = null, text: Boolean = false, d: String = "",
    ) = FormatSpec(id, label, cat, ext, mime, sigs, container, text, d)

    private val DOC = FormatCategory.DOCUMENT
    private val SS = FormatCategory.SPREADSHEET
    private val DB = FormatCategory.DATABASE

    val specs: List<FormatSpec> = listOf(
        f("wr1", "Lotus Symphony worksheet", SS, listOf("wr1", "wrk", "wr2"),
            d = "Lotus Symphony worksheet."),
        f("ws4", "WordStar document", DOC, listOf("ws4", "ws5", "ws6", "ws7"),
            d = "WordStar word-processor document."),
        f("wbk", "Word backup document", DOC, mime = listOf("application/msword"),
            d = "Automatic backup copy of a Microsoft Word document."),
        f("wiz", "Word wizard", DOC, mime = listOf("application/msword"),
            d = "Microsoft Word wizard file."),
        f("xlb", "Excel toolbar settings", SS, mime = listOf("application/vnd.ms-excel"),
            d = "Excel customised toolbar configuration."),
        f("xlc", "Excel chart / macro sheet", SS, listOf("xlc", "xlm"),
            mime = listOf("application/vnd.ms-excel"),
            d = "Excel chart (.xlc) or Excel 4.0 macro sheet (.xlm)."),
        f("smf", "StarMath formula", DOC, mime = listOf("application/vnd.stardivision.math"),
            d = "StarOffice / OpenOffice Math formula."),
        f("sgv", "StarOffice graphic", DOC, mime = listOf("application/vnd.stardivision.draw"),
            d = "StarOffice StarDraw graphic."),
        f("mml", "MathML file", DOC, mime = listOf("application/mathml+xml"), text = true,
            d = "Mathematical Markup Language, an XML application."),
        f("odft", "OpenDocument formula template", DOC,
            container = "zip",
            mime = listOf("application/vnd.oasis.opendocument.formula-template"),
            d = "ZIP-based OpenDocument formula template."),
        f("fp3", "FileMaker Pro 3 database", DB, d = "Legacy FileMaker Pro database."),
        f("dbc", "FoxPro database container", DB, d = "Visual FoxPro database container."),
        f("fpt", "FoxPro memo file", DB, d = "FoxPro memo (long text) storage for a DBF table."),
        f("ndx", "dBase index", DB, listOf("ndx", "ntx"),
            d = "dBase / Clipper index file."),
        f("mdw", "Access workgroup / add-in database", DB,
            listOf("mdw", "mda", "mde", "mdt", "ade"),
            mime = listOf("application/x-msaccess"),
            d = "Microsoft Access workgroup information, add-in or compiled database."),
        f("laccdb", "Access lock file", DB,
            d = "Record-locking file created while an Access database is open."),
        f("px", "Paradox index", DB, listOf("px", "xg0", "yg0", "mb"),
            d = "Paradox primary index, secondary index and BLOB files (.db is shared and omitted)."),
        f("tps", "TopSpeed database", DB, d = "Clarion TopSpeed database file."),
        f("btr", "Btrieve file", DB, listOf("btr", "btv"),
            d = "Btrieve transactional database file."),
    )
}
