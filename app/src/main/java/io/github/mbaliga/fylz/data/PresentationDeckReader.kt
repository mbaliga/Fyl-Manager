package io.github.mbaliga.fylz.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.util.Locale

/** One slide's text, in the order the slide declares it. */
data class DeckSlide(
    val number: Int,
    val title: String?,
    val body: List<String>,
) {
    val empty: Boolean get() = title.isNullOrBlank() && body.isEmpty()
}

/**
 * A deck read slide by slide.
 *
 * [note] is not decoration: this reader returns the TEXT of each slide, in slide order, and says
 * so, because the alternative -- calling an outline a "presentation preview" -- would advertise a
 * rendering that does not exist.
 */
data class PresentationDeck(
    val formatLabel: String,
    val slides: List<DeckSlide>,
    val truncated: Boolean,
    val note: String,
)

/** Presentation containers Fylz can actually walk. */
enum class DeckKind { OOXML, OPEN_DOCUMENT }

/**
 * Slide-by-slide text extraction from PowerPoint (.pptx/.pptm/.ppsx) and OpenDocument (.odp/.otp).
 *
 * Slide ORDER comes from the deck itself, not from part names: `ppt/presentation.xml` lists slide
 * relationship ids in presentation order, and a deck whose slides have been reordered keeps
 * `slide1.xml` in the middle. Sorting the parts by number -- the obvious shortcut -- silently
 * reorders the deck, so that path is only the fallback for a deck with no usable relationship
 * table.
 *
 * Titles come from each shape's placeholder role (`<p:ph type="title"/>`, or ODF's
 * `presentation:class="title"`), so the title is the one the author designated rather than
 * whichever line happened to come first.
 *
 * What this does not do: render. There is no slide image, no layout, no theme, and speaker notes
 * are not read. [PresentationDeck.note] states that, and the preview shows it.
 */
class PresentationDeckReader(open: () -> InputStream) {

    private val parts = ZipXmlParts(open)

    /** Reads an OOXML deck in presentation order. */
    fun readOoxml(): PresentationDeck {
        val order = SlideOrderHandler()
        val relationships = RelationshipHandler()
        val discovered = mutableListOf<String>()
        parts.walk { name, stream ->
            val lower = name.lowercase(Locale.ROOT)
            when {
                lower == "ppt/presentation.xml" -> parts.parse(stream, order)
                lower == "ppt/_rels/presentation.xml.rels" -> parts.parse(stream, relationships)
                lower.startsWith("ppt/slides/") && lower.endsWith(".xml") && !lower.contains("/_rels/") ->
                    discovered += name
            }
            true
        }
        val ordered = order.ids
            .mapNotNull { id -> relationships.targets[id]?.let { resolveOoxmlTarget("ppt", it) } }
            .ifEmpty { discovered.sortedBy { part -> part.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE } }
        require(ordered.isNotEmpty()) { "This presentation contains no slides." }
        val wanted = ordered.map { it.lowercase(Locale.ROOT) }.toSet()
        val byPart = HashMap<String, SlideHandler>()
        parts.walk { name, stream ->
            val lower = name.lowercase(Locale.ROOT)
            if (lower in wanted && lower !in byPart) {
                val handler = SlideHandler()
                parts.parse(stream, handler)
                byPart[lower] = handler
            }
            true
        }
        val slides = ordered.take(MAX_SLIDES).mapIndexedNotNull { index, part ->
            byPart[part.lowercase(Locale.ROOT)]?.let { DeckSlide(index + 1, it.title, it.body.toList()) }
        }
        require(slides.isNotEmpty()) { "This presentation's slide parts are missing from the container." }
        return PresentationDeck(
            formatLabel = "PowerPoint presentation",
            slides = slides,
            truncated = ordered.size > MAX_SLIDES,
            note = SLIDE_TEXT_NOTE,
        )
    }

    /** Reads an OpenDocument deck in document order. */
    fun readOpenDocument(): PresentationDeck {
        val handler = OpenDocumentDeckHandler()
        val found = parts.walk { name, stream ->
            if (name.equals("content.xml", ignoreCase = true)) {
                parts.parse(stream, handler)
                false
            } else {
                true
            }
        }
        require(found) { "This OpenDocument container has no content.xml." }
        require(handler.slides.isNotEmpty()) { "This presentation contains no slides." }
        return PresentationDeck(
            formatLabel = "OpenDocument presentation",
            slides = handler.slides.toList(),
            truncated = handler.truncated,
            note = SLIDE_TEXT_NOTE,
        )
    }

    private class SlideOrderHandler : DefaultHandler() {
        val ids = mutableListOf<String>()
        private var inList = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "p:sldIdLst" -> inList = true
                "p:sldId" -> if (inList && ids.size < MAX_SLIDES) {
                    (attributes.getValue("r:id") ?: attributes.getValue("id"))?.let { ids += it }
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            // Slide order is the only thing wanted from presentation.xml; the rest of it -- sizes,
            // masters, embedded fonts -- is never read.
            if (qName == "p:sldIdLst") throw XmlPartStop()
        }
    }

    private class SlideHandler : DefaultHandler() {
        var title: String? = null
        val body = mutableListOf<String>()

        private var inShape = false
        private var shapeIsTitle = false
        private val shapeParagraphs = mutableListOf<String>()
        private val paragraph = StringBuilder()
        private val run = StringBuilder()
        private var capturing = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "p:sp" -> {
                    inShape = true
                    shapeIsTitle = false
                    shapeParagraphs.clear()
                }
                "p:ph" -> if (inShape && attributes.getValue("type") in TITLE_PLACEHOLDERS) shapeIsTitle = true
                "a:p" -> paragraph.setLength(0)
                "a:t" -> { capturing = true; run.setLength(0) }
                // A soft line break inside a paragraph is a space, not a new bullet.
                "a:br" -> paragraph.append(' ')
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing) run.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "a:t" -> { capturing = false; paragraph.append(run) }
                "a:p" -> {
                    val text = paragraph.toString().trim()
                    paragraph.setLength(0)
                    if (text.isEmpty()) return
                    // Text outside any shape -- table cells, diagram nodes -- is still the slide's
                    // text and belongs in its body rather than being dropped.
                    if (inShape) shapeParagraphs += text else addBody(text)
                }
                "p:sp" -> {
                    inShape = false
                    if (shapeIsTitle && title.isNullOrBlank() && shapeParagraphs.isNotEmpty()) {
                        title = shapeParagraphs.joinToString(" ").take(MAX_PARAGRAPH_CHARS)
                    } else {
                        shapeParagraphs.forEach(::addBody)
                    }
                    shapeParagraphs.clear()
                }
            }
        }

        private fun addBody(text: String) {
            if (body.size < MAX_PARAGRAPHS) body += text.take(MAX_PARAGRAPH_CHARS)
        }
    }

    private class OpenDocumentDeckHandler : DefaultHandler() {
        val slides = mutableListOf<DeckSlide>()
        var truncated = false

        private var inPage = false
        private var notesDepth = 0
        private var frameClass: String? = null
        private var title: String? = null
        private val body = mutableListOf<String>()
        private val paragraph = StringBuilder()
        private var capturing = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "draw:page" -> {
                    inPage = true
                    title = null
                    body.clear()
                }
                // Speaker notes live inside the page but are not the slide; skipping them keeps a
                // slide's body the text the audience actually sees.
                "presentation:notes" -> notesDepth += 1
                "draw:frame" -> frameClass = attributes.getValue("presentation:class")
                "text:p" -> if (inPage && notesDepth == 0) { capturing = true; paragraph.setLength(0) }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing) paragraph.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "text:p" -> if (capturing) {
                    capturing = false
                    val text = paragraph.toString().trim()
                    paragraph.setLength(0)
                    if (text.isEmpty()) return
                    if (frameClass in TITLE_FRAME_CLASSES && title.isNullOrBlank()) {
                        title = text.take(MAX_PARAGRAPH_CHARS)
                    } else if (body.size < MAX_PARAGRAPHS) {
                        body += text.take(MAX_PARAGRAPH_CHARS)
                    }
                }
                "presentation:notes" -> if (notesDepth > 0) notesDepth -= 1
                "draw:frame" -> frameClass = null
                "draw:page" -> {
                    inPage = false
                    if (slides.size >= MAX_SLIDES) {
                        truncated = true
                        throw XmlPartStop()
                    }
                    slides += DeckSlide(slides.size + 1, title, body.toList())
                }
            }
        }
    }

    companion object {
        const val MAX_SLIDES = 500
        const val MAX_PARAGRAPHS = 200
        const val MAX_PARAGRAPH_CHARS = 2_000

        private const val SLIDE_TEXT_NOTE =
            "Slide text, slide by slide, in the deck's own order. Fylz reads what each slide says; " +
                "it does not render slide graphics, layout or speaker notes."

        private val TITLE_PLACEHOLDERS = setOf("title", "ctrTitle")
        private val TITLE_FRAME_CLASSES = setOf("title", "subtitle")

        /** The reader for a presentation extension, or null when Fylz has none for it. */
        fun deckKind(extension: String): DeckKind? = when (extension.lowercase(Locale.ROOT)) {
            "pptx", "pptm", "ppsx" -> DeckKind.OOXML
            "odp", "otp" -> DeckKind.OPEN_DOCUMENT
            // .ppt is the pre-2007 binary format and .key is Apple's; nothing bundled reads either,
            // so they keep the universal inspector rather than a deck view Fylz cannot fill.
            else -> null
        }
    }
}
