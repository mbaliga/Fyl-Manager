package io.github.mbaliga.fylz.data

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.XMLReader
import org.xml.sax.helpers.DefaultHandler
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Streaming, bounded access to the XML parts inside a ZIP-based document container.
 *
 * OOXML (.xlsx/.pptx) and OpenDocument (.ods/.odp) are both a ZIP of XML parts, and every reader
 * over them needs the same three things: walk the container without expanding it, parse one part
 * with a hardened SAX reader, and stop parsing the moment the handler has what it needs. That is
 * all this is -- shared so the workbook reader and the deck reader cannot drift apart on the
 * safety details, which is exactly where a copy would rot.
 *
 * The parts are never written to disk and never fully buffered: a handler sees them as a stream
 * that refuses to yield more than [MAX_PART_BYTES].
 */
class ZipXmlParts(private val open: () -> InputStream) {

    /**
     * Walks the container once, handing each entry's name and a bounded stream to [visit].
     *
     * Returning false from [visit] stops the walk early, and [walk]'s own return value reports
     * whether that happened -- which is how a caller tells "the part I needed was there" apart
     * from "I read the whole container and it was not".
     */
    fun walk(visit: (String, InputStream) -> Boolean): Boolean {
        open().use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    require(entries <= MAX_ENTRIES) { "This container has too many entries to inspect safely." }
                    if (!entry.isDirectory) {
                        val name = entry.name.replace('\\', '/').trimStart('/')
                        if (!visit(name, BoundedStream(NonClosingStream(zip), MAX_PART_BYTES))) return true
                    }
                    zip.closeEntry()
                }
            }
        }
        return false
    }

    /** Parses one part, treating an [XmlPartStop] thrown by [handler] as a successful early exit. */
    fun parse(stream: InputStream, handler: DefaultHandler) {
        val reader = newReader()
        reader.contentHandler = handler
        try {
            reader.parse(InputSource(stream))
        } catch (failure: SAXException) {
            // A handler that already has everything it needs stops the parse by throwing. Some
            // parser implementations hand that back wrapped, so the sentinel is looked for through
            // the chain rather than by type alone; anything else is a real failure and propagates.
            if (!failure.isStopSignal()) throw failure
        }
    }

    private fun newReader(): XMLReader {
        val factory = SAXParserFactory.newInstance()
        // Namespace-unaware on purpose: OOXML and ODF both address parts by fixed qualified names
        // ("table:table-cell", "r:id", "a:t"), so matching those literally is both simpler and
        // cheaper than resolving prefixes the specifications already pin.
        factory.isNamespaceAware = false
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, value) -> runCatching { factory.setFeature(feature, value) } }
        val reader = factory.newSAXParser().xmlReader
        // The belt to those braces: whichever feature flags a given platform's parser does or does
        // not honour, an entity resolver that hands back an empty document can never fetch a file
        // or a URL on an untrusted document's behalf.
        reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
        return reader
    }

    private fun SAXException.isStopSignal(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < STOP_UNWRAP_DEPTH) {
            if (current is XmlPartStop || current.message == STOP) return true
            current = current.cause ?: (current as? SAXException)?.exception
            depth += 1
        }
        return false
    }

    /** Shields the walk's own ZIP stream from a parser that closes whatever it is handed. */
    private class NonClosingStream(source: InputStream) : FilterInputStream(source) {
        override fun close() = Unit
    }

    /** Refuses to hand out more than [maxBytes] from any one container part. */
    private class BoundedStream(source: InputStream, private val maxBytes: Long) : FilterInputStream(source) {
        private var read = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count(1L)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val value = super.read(buffer, offset, length)
            if (value > 0) count(value.toLong())
            return value
        }

        private fun count(bytes: Long) {
            read += bytes
            if (read > maxBytes) throw SAXException("This document part exceeds the preview size limit.")
        }
    }

    companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_PART_BYTES = 256L * 1024L * 1024L

        internal const val STOP = "fylz-preview-stop"
        private const val STOP_UNWRAP_DEPTH = 8
    }
}

/** Thrown by a handler that has read everything it needs; [ZipXmlParts.parse] treats it as done. */
class XmlPartStop : SAXException(ZipXmlParts.STOP)

/**
 * The `_rels` part both OOXML families use to map a relationship id to the part it points at.
 *
 * Identical in workbooks and decks -- `<Relationship Id="rId3" Target="slides/slide1.xml"/>` --
 * which is why one handler serves both.
 */
class RelationshipHandler(private val maxRelationships: Int = 4_096) : DefaultHandler() {
    val targets = HashMap<String, String>()

    override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
        if (qName != "Relationship" || targets.size >= maxRelationships) return
        val id = attributes.getValue("Id") ?: return
        val target = attributes.getValue("Target") ?: return
        targets[id] = target
    }
}

/**
 * Resolves an OOXML relationship target against the part directory that declared it.
 *
 * Targets are written either absolutely ("/ppt/slides/slide1.xml") or relative to the declaring
 * part's folder ("slides/slide1.xml", or "../notesSlides/notesSlide1.xml"), so both spellings have
 * to collapse to the same container entry name or the second pass finds nothing.
 */
fun resolveOoxmlTarget(base: String, target: String): String {
    val cleaned = target.replace('\\', '/')
    if (cleaned.startsWith("/")) return cleaned.trimStart('/')
    val segments = mutableListOf<String>()
    segments += base.split('/').filter { it.isNotEmpty() }
    for (segment in cleaned.split('/')) {
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return segments.joinToString("/")
}
