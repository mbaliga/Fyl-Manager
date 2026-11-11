package io.github.mbaliga.fylz.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Budgets what each widget draws against the size its layout is declared for.
 *
 * A launcher switches to a layout as soon as the widget reaches that layout's ideal size
 * ([WidgetSize]), so each widget's fullest content must fit that exact height at 1.0x font scale.
 * The geometry (paddings, margins, text sizes, bar heights, row minimum heights) is read from the
 * layouts themselves; text height uses Roboto's line metrics with the default font padding: the
 * first line is 1.327 x the text size, each further line 1.172 x.
 *
 * What each widget puts in each slot mirrors WidgetRenderers.kt and the models' capacities; change
 * them together. Captions are counted by their explicit lines (renderers join facts with a line
 * break). A caption that also wraps at a narrow width, or text scaled past 1.0x, is cut by the
 * body's clip; WidgetResourceContractTest pins the footer outside that clip.
 */
class WidgetFitTest {
    private val res = File("src/main/res")
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun layout(name: String): Element {
        val file = File(res, "layout/$name.xml")
        assertTrue("missing ${file.path}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.attr(name: String): String = getAttributeNS(androidNs, name)

    private fun Element.byId(id: String): Element {
        val nodes = getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            if (element.attr("id") == "@+id/$id") return element
        }
        error("no @+id/$id")
    }

    private fun Element.dp(name: String): Float = attr(name).takeIf { it.isNotEmpty() }?.removeSuffix("dp")?.toFloat() ?: 0f

    private fun Element.sp(): Float = attr("textSize").removeSuffix("sp").toFloat()

    private val Element.parent: Element get() = parentNode as Element

    private fun lines(sp: Float, count: Int): Float = if (count <= 0) 0f else sp * (FIRST_LINE + NEXT_LINE * (count - 1))

    /** Geometry of one panel template (stat, list medium or list large). */
    private inner class Panel(name: String) {
        private val root = layout(name)
        val padding = root.dp("paddingTop") + root.dp("paddingBottom")
        val header = lines(root.byId("widget_title").sp(), 1)
        val footer = lines(root.byId("widget_footer").sp(), 1)

        fun headline(): Float {
            val text = root.byId("widget_headline")
            val glyph = root.byId("widget_headline_glyph")
            return text.parent.dp("layout_marginTop") + maxOf(lines(text.sp(), 1), lines(glyph.sp(), 1))
        }

        fun caption(count: Int): Float = lines(root.byId("widget_caption").sp(), count)

        fun bar(): Float = root.byId("widget_bar").let { it.dp("layout_marginTop") + it.dp("layout_height") }

        fun row(meta: Boolean, bar: Boolean): Float {
            val container = root.byId("widget_row_1")
            var content = maxOf(
                lines(root.byId("widget_row_1_title").sp(), 1),
                lines(root.byId("widget_row_1_trailing").sp(), 1),
                lines(root.byId("widget_row_1_glyph").sp(), 1),
            )
            if (meta) content += lines(root.byId("widget_row_1_meta").sp(), 1)
            if (bar) content += root.byId("widget_row_1_bar").let { it.dp("layout_marginTop") + it.dp("layout_height") }
            return container.dp("layout_marginTop") + maxOf(container.dp("minHeight"), content)
        }

        fun footerWithAction(): Float = maxOf(footer, root.byId("widget_action").dp("minHeight"))
    }

    private val stat = Panel("widget_stat")
    private val medium = Panel("widget_list_medium")
    private val large = Panel("widget_list_large")

    private fun assertFits(size: WidgetSize, cases: Map<String, Float>) {
        cases.forEach { (case, height) ->
            assertTrue(
                "$case needs %.1fdp but $size is %.0fdp tall".format(height, size.heightDp),
                height <= size.heightDp,
            )
        }
    }

    @Test
    fun `compact content fits 2x1`() {
        val root = layout("widget_compact")
        val line = maxOf(
            root.byId("widget_compact_icon").dp("layout_height"),
            lines(root.byId("widget_compact_glyph").sp(), 1),
            lines(root.byId("widget_compact_text").sp(), 1),
        )
        val bar = root.byId("widget_compact_bar").let { it.dp("layout_marginTop") + it.dp("layout_height") }
        assertFits(WidgetSize.COMPACT, mapOf("compact with a bar" to root.dp("paddingTop") + root.dp("paddingBottom") + line + bar))
    }

    @Test
    fun `small content fits 2x2`() = with(stat) {
        val base = padding + header + footer
        assertFits(
            WidgetSize.SMALL,
            mapOf(
                "Space (free, total and share used, bar)" to base + headline() + caption(1) + bar(),
                "Backups (status, its time and the next window)" to base + headline() + caption(2),
                "Recovery (bin count, size and attention)" to base + headline() + caption(2),
                "In progress (percentage, task, bar)" to base + headline() + caption(1) + bar(),
                "Index (count, status line)" to base + headline() + caption(1),
                // The access hint always wraps at this width.
                "Quick folders not connected" to base + headline() + caption(2),
            ),
        )
    }

    @Test
    fun `medium content fits 4x2`() = with(medium) {
        val base = padding + header + footer
        val plain = row(meta = false, bar = false)
        val barred = row(meta = false, bar = true)
        assertFits(
            WidgetSize.MEDIUM,
            mapOf(
                "Space (${SpaceWidgetModel.capacity(WidgetSize.MEDIUM)} volumes with bars)" to
                    base + SpaceWidgetModel.capacity(WidgetSize.MEDIUM) * barred,
                "Backups (status row, new-media row with a bar)" to base + plain + barred,
                "Recovery (bin row, attention row)" to base + 2 * plain,
                "In progress (${OperationsWidgetModel.capacity(WidgetSize.MEDIUM)} tasks with bars)" to
                    base + OperationsWidgetModel.capacity(WidgetSize.MEDIUM) * barred,
                "Index (status row, scope row)" to base + 2 * plain,
            ),
        )
    }

    @Test
    fun `large content fits 4x3`() = with(large) {
        val base = padding + header + footer
        val full = row(meta = true, bar = true)
        val twoLine = row(meta = true, bar = false)
        assertFits(
            WidgetSize.LARGE,
            mapOf(
                "Space (${SpaceWidgetModel.capacity(WidgetSize.LARGE)} volume rows)" to
                    base + SpaceWidgetModel.capacity(WidgetSize.LARGE) * full,
                "Backups (headline, two-line caption, ${BackupsWidgetModel.MAX_ROWS} plan row)" to
                    base + headline() + caption(2) + BackupsWidgetModel.MAX_ROWS * full,
                "Recovery (bin row, attention row)" to base + 2 * twoLine,
                "In progress (${OperationsWidgetModel.capacity(WidgetSize.LARGE)} task rows)" to
                    base + OperationsWidgetModel.capacity(WidgetSize.LARGE) * full,
                "Index (headline, status line, scope row, Rebuild)" to
                    padding + header + headline() + caption(1) + twoLine + footerWithAction(),
            ),
        )
    }

    @Test
    fun `no widget asks a list layout for more rows than it holds`() {
        listOf(
            SpaceWidgetModel.capacity(WidgetSize.MEDIUM),
            SpaceWidgetModel.capacity(WidgetSize.LARGE),
            OperationsWidgetModel.capacity(WidgetSize.MEDIUM),
            OperationsWidgetModel.capacity(WidgetSize.LARGE),
            BackupsWidgetModel.MAX_ROWS,
        ).forEach { assertTrue("$it rows > ${WidgetTemplates.LIST_ROWS}", it <= WidgetTemplates.LIST_ROWS) }
    }

    @Test
    fun `pad grids fit their sizes with 48dp pads`() {
        fun height(name: String): Float {
            val root = layout(name)
            val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
            val rows = children.sumOf { child ->
                val pads = if (child.attr("id").startsWith("@+id/widget_pad_")) {
                    listOf(child)
                } else {
                    (0 until child.childNodes.length).map { child.childNodes.item(it) }.filterIsInstance<Element>()
                }
                (child.dp("layout_marginTop") + pads.maxOf { it.dp("minHeight") }).toDouble()
            }.toFloat()
            return root.dp("padding") * 2 + rows
        }
        assertFits(WidgetSize.SMALL, mapOf("2 pads" to height("widget_pads_small")))
        assertFits(WidgetSize.MEDIUM, mapOf("4 pads" to height("widget_pads_medium")))
        assertFits(WidgetSize.LARGE, mapOf("6 pads" to height("widget_pads_large")))
    }

    private companion object {
        /** Roboto (top to bottom with font padding) over the text size. */
        const val FIRST_LINE = 1.327f

        /** Roboto (ascent to descent) over the text size, for each line after the first. */
        const val NEXT_LINE = 1.172f
    }
}
