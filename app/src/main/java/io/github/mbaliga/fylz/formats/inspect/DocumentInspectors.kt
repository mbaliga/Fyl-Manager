package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.FormatInspector

/** Every document-format inspector in this package. */
object DocumentInspectors {
    val all: List<FormatInspector> = listOf(
        DocumentInspectorsPdf,
        DocumentInspectorsOoxml,
        DocumentInspectorsOdf,
        DocumentInspectorsEpub,
        DocumentInspectorsRtf,
        DocumentInspectorsOle,
        DocumentInspectorsMobi,
    )
}
