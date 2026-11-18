package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*

/** Minimal specs for formats that inspectors cover but no area catalog defines. */
object IntegrationExtrasCatalog {
    val specs: List<FormatSpec> = listOf(
        FormatSpec(
            id = "gpx", label = "GPS Exchange Format", category = FormatCategory.GIS,
            extensions = listOf("gpx"), mimeTypes = listOf("application/gpx+xml"), text = true,
            description = "XML interchange format for GPS waypoints, routes and tracks.",
        ),
    )
}
