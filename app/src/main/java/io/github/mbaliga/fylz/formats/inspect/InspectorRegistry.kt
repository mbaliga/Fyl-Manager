package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Every inspector in one place, looked up by format id. */
object InspectorRegistry {
    val all: List<FormatInspector> =
        ArchiveInspectors.all +
            BinaryInspectors.all +
            CadInspectors.all +
            DocumentInspectors.all +
            FontGameInspectors.all +
            GisScienceInspectors.all +
            ImageInspectors.all +
            MediaInspectors.all +
            TextCryptoInspectors.all

    private val byFormat: Map<String, List<FormatInspector>> by lazy {
        val m = LinkedHashMap<String, MutableList<FormatInspector>>()
        for (i in all) for (id in i.formatIds) m.getOrPut(id) { mutableListOf() }.add(i)
        m
    }

    fun forFormat(id: String): List<FormatInspector> = byFormat[id].orEmpty()

    fun inspect(formatId: String, source: ByteSource, fileName: String?): InspectionResult? {
        for (i in forFormat(formatId)) {
            val r = runCatching { i.inspect(source, fileName) }.getOrNull()
            if (r != null) return r
        }
        return null
    }
}
