package io.github.mbaliga.fylz.formats

import org.junit.Test

class CatalogDumpTest {
    @Test fun dump() {
        val lines = FormatCatalog.all.sortedBy { it.id }.map {
            "${it.id}|${it.category}|${it.extensions.joinToString(",")}|${it.signatures.size}|${it.label}"
        }
        java.io.File("/home/user/fylz-stage/catalog-dump.txt").writeText(lines.joinToString("\n") + "\n")
    }
}
