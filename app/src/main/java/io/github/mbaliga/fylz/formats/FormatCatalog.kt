package io.github.mbaliga.fylz.formats

import io.github.mbaliga.fylz.formats.db.*

/**
 * The assembled catalog of every format Fylz knows. Each area contributes a `*Catalog` object
 * from `formats/db`; [all] concatenates them.
 */
object FormatCatalog {
    val all: List<FormatSpec> =
        ArchivesCatalog.specs +
            AudioVideoCatalog.specs +
            CadModelCatalog.specs +
            DataConfigCatalog.specs +
            DocumentsCatalog.specs +
            EmailCryptoSystemCatalog.specs +
            ExecutablesCatalog.specs +
            GamesNicheCatalog.specs +
            GisScienceCatalog.specs +
            ImagesCatalog.specs +
            IntegrationExtrasCatalog.specs +
            SourceCodeCatalog.specs
    val sniffer: FormatSniffer by lazy { FormatSniffer(all) }
}
