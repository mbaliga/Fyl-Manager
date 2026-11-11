package io.github.mbaliga.fylz.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Static checks over the widget resources, run on the JVM.
 *
 * The most important one is the template contract: [WidgetTemplates] sets views by id, and a
 * RemoteViews action aimed at an id a layout lacks makes the launcher draw its "Problem loading
 * widget" tile instead of the widget. No device is needed to catch that, so it is pinned here,
 * together with the layout rules from docs/widgets.md (RemoteViews-safe view classes, the
 * `@android:id/background` root, text no smaller than 12sp, 48dp touch targets, a footer that
 * cannot be clipped) and the provider declarations launchers read. WidgetFitTest budgets the
 * content of each size.
 */
class WidgetResourceContractTest {
    private val res = File("src/main/res")
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun parse(file: File): Element {
        assertTrue("missing ${file.path} (tests run from the app module directory)", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.descendants(): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = getElementsByTagName("*")
        for (i in 0 until nodes.length) out += nodes.item(i) as Element
        return out
    }

    private fun Element.attr(name: String): String = getAttributeNS(androidNs, name)

    private fun ids(layout: String): Set<String> =
        (listOf(parse(File(res, "layout/$layout.xml"))) + parse(File(res, "layout/$layout.xml")).descendants())
            .map { it.attr("id") }
            .filter { it.startsWith("@+id/") }
            .map { it.removePrefix("@+id/") }
            .toSet()

    private val widgetLayouts: List<File>
        get() = File(res, "layout").listFiles { file -> file.name.startsWith("widget_") }.orEmpty().sortedBy { it.name }

    @Test
    fun `every widget layout uses only RemoteViews-safe views and the background root`() {
        val allowed = setOf("LinearLayout", "FrameLayout", "RelativeLayout", "GridLayout", "TextView", "ImageView", "ProgressBar", "Button", "ImageButton")
        assertTrue(widgetLayouts.size >= 14)
        widgetLayouts.forEach { file ->
            val root = parse(file)
            assertEquals("${file.name} root id", "@android:id/background", root.attr("id"))
            (listOf(root) + root.descendants()).forEach { view ->
                assertTrue("${file.name}: <${view.tagName}> is not allowed in RemoteViews", view.tagName in allowed)
                val size = view.attr("textSize")
                if (size.isNotEmpty()) {
                    assertTrue("${file.name}: textSize $size must be in sp", size.endsWith("sp"))
                    assertTrue("${file.name}: textSize $size is below 12sp", size.removeSuffix("sp").toFloat() >= 12f)
                }
                if (view.attr("id").startsWith("@+id/widget_pad_") && view.tagName == "LinearLayout") {
                    assertEquals("${file.name}: pad touch target", "48dp", view.attr("minHeight"))
                }
            }
        }
    }

    @Test
    fun `templates contain every view WidgetTemplates sets`() {
        fun rows(n: Int, meta: Boolean) = (1..n).flatMap { i ->
            listOf("widget_row_$i", "widget_row_${i}_glyph", "widget_row_${i}_title", "widget_row_${i}_trailing", "widget_row_${i}_bar") +
                if (meta) listOf("widget_row_${i}_meta") else emptyList()
        }
        fun pads(n: Int, meta: Boolean) = (1..n).flatMap { i ->
            listOf("widget_pad_$i", "widget_pad_${i}_label") + if (meta) listOf("widget_pad_${i}_meta") else emptyList()
        }
        val expected = mapOf(
            "widget_compact" to listOf("widget_compact_icon", "widget_compact_glyph", "widget_compact_text", "widget_compact_bar"),
            "widget_stat" to listOf("widget_title", "widget_headline_glyph", "widget_headline", "widget_caption", "widget_bar", "widget_footer"),
            "widget_list_medium" to listOf("widget_title", "widget_trailing", "widget_footer") + rows(WidgetTemplates.LIST_ROWS, meta = false),
            "widget_list_large" to listOf(
                "widget_title", "widget_trailing", "widget_headline_row", "widget_headline_glyph", "widget_headline",
                "widget_caption", "widget_bar", "widget_action", "widget_footer",
            ) + rows(WidgetTemplates.LIST_ROWS, meta = true),
            "widget_pads_small" to pads(2, meta = false),
            "widget_pads_medium" to pads(4, meta = false),
            "widget_pads_large" to pads(6, meta = false),
        )
        expected.forEach { (layout, required) ->
            val present = ids(layout)
            val missing = required.filterNot(present::contains)
            assertTrue("$layout is missing $missing", missing.isEmpty())
        }
        // The MEDIUM list has no second line; WidgetTemplates must not be asked to fill one there.
        assertTrue(ids("widget_list_medium").none { it.endsWith("_meta") })
    }

    @Test
    fun `the large template's action is a full touch target`() {
        val action = parse(File(res, "layout/widget_list_large.xml")).descendants().single { it.attr("id") == "@+id/widget_action" }
        assertEquals("48dp", action.attr("minHeight"))
    }

    @Test
    fun `every row the large template can make tappable is a full touch target`() {
        // WidgetTemplates attaches row clicks in the LARGE list only; MEDIUM rows are never targets.
        val rows = parse(File(res, "layout/widget_list_large.xml")).descendants()
            .filter { it.attr("id").matches(Regex("@\\+id/widget_row_\\d+")) }
        assertEquals(WidgetTemplates.LIST_ROWS, rows.size)
        rows.forEach { assertEquals(it.attr("id"), "48dp", it.attr("minHeight")) }
    }

    @Test
    fun `panel templates pin the title and footer outside a clipping body`() {
        listOf("widget_stat", "widget_list_medium", "widget_list_large").forEach { name ->
            val root = parse(File(res, "layout/$name.xml"))
            val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
            assertEquals("$name: title, body, footer", 3, children.size)
            val (header, body, footer) = children
            fun Element.holds(id: String) = attr("id") == "@+id/$id" || descendants().any { it.attr("id") == "@+id/$id" }
            assertTrue("$name: the first child holds the title", header.holds("widget_title"))
            assertEquals("$name: body height", "0dp", body.attr("layout_height"))
            assertEquals("$name: body weight", "1", body.attr("layout_weight"))
            assertTrue("$name: the body holds no footer", !body.holds("widget_footer"))
            assertTrue("$name: the last child holds the footer", footer.holds("widget_footer"))
        }
    }

    @Test
    fun `every provider is resizable, pushed rather than polled, and previewed`() {
        val infos = File(res, "xml").listFiles { file -> file.name.matches(Regex("widget_.*_info\\.xml")) }.orEmpty()
        assertEquals(WidgetKind.entries.size, infos.size)
        infos.forEach { file ->
            val info = parse(file)
            assertEquals(file.name, "0", info.attr("updatePeriodMillis"))
            assertEquals(file.name, "horizontal|vertical", info.attr("resizeMode"))
            assertEquals(file.name, "home_screen", info.attr("widgetCategory"))
            listOf("description", "previewLayout", "previewImage", "initialLayout", "minWidth", "minHeight", "minResizeWidth", "minResizeHeight", "targetCellWidth", "targetCellHeight")
                .forEach { assertTrue("${file.name} lacks android:$it", info.attr(it).isNotEmpty()) }
            val preview = info.attr("previewLayout").removePrefix("@layout/")
            assertTrue("${file.name}: preview $preview missing", File(res, "layout/$preview.xml").isFile)
        }
    }

    @Test
    fun `providers are exported for launchers and everything else stays private`() {
        val manifest = parse(File("src/main/AndroidManifest.xml"))
        val receivers = manifest.descendants().filter { it.tagName == "receiver" }.associateBy { it.attr("name") }
        WidgetKind.entries.forEach { kind ->
            val receiver = receivers[".widget.${kind.provider.simpleName}"]
            requireNotNull(receiver) { "${kind.provider.simpleName} is not declared" }
            assertEquals("true", receiver.attr("exported"))
            assertTrue(receiver.attr("label").startsWith("@string/"))
            val actions = receiver.descendants().filter { it.tagName == "action" }.map { it.attr("name") }
            assertTrue(actions.contains("android.appwidget.action.APPWIDGET_UPDATE"))
            val meta = receiver.descendants().single { it.tagName == "meta-data" }
            assertEquals("android.appwidget.provider", meta.attr("name"))
        }
        listOf(".widget.WidgetTickReceiver", ".widget.WidgetActionReceiver").forEach { name ->
            assertEquals(name, "false", receivers.getValue(name).attr("exported"))
        }
    }

    @Test
    fun `day and night palettes define the same widget colours`() {
        fun names(path: String) = parse(File(res, path)).descendants().filter { it.tagName == "color" }.map { it.getAttribute("name") }.toSet()
        val day = names("values/widget_colors.xml")
        assertTrue(day.isNotEmpty())
        assertEquals(day, names("values-night/widget_colors.xml"))
    }
}
