package io.github.mbaliga.fylz.formats

/**
 * The assembled catalog of every format Fylz knows. Each area contributes a `*Catalog` object
 * from `formats/db`; [all] concatenates them. (Assembled after the catalogs are written.)
 */
object FormatCatalog {
    val all: List<FormatSpec> = emptyList()
    val sniffer: FormatSniffer by lazy { FormatSniffer(all) }
}
