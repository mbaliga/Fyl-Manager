package io.github.mbaliga.fylz.desktop

import io.github.mbaliga.fylz.canvas.CanvasLayoutPolicy
import io.github.mbaliga.fylz.canvas.TilePlacement
import io.github.mbaliga.fylz.ui.desktop.WidgetRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round

/**
 * Pure JVM -- every function under test is a pure function of [TilePlacement] and primitives, no
 * Uri/Context involved, unlike [CanvasLayoutPolicyTest] which needs Robolectric only for the
 * [io.github.mbaliga.fylz.model.FileEntry] fixtures its own [CanvasLayoutPolicy] functions take.
 */
class DesktopPolicyTest {

    // ── clamp / raise (delegate to CanvasLayoutPolicy) -- shortcut tiles only ───────────

    @Test
    fun `clamp matches CanvasLayoutPolicy's own band exactly`() {
        val p = TilePlacement(x = -5f, y = 5f, z = 1)
        assertEquals(CanvasLayoutPolicy.clamp(p), DesktopPolicy.clamp(p))
    }

    @Test
    fun `clamp leaves an already in-band placement untouched`() {
        val p = TilePlacement(x = 0.5f, y = 0.5f, z = 3)
        assertEquals(p, DesktopPolicy.clamp(p))
    }

    @Test
    fun `raise puts a tile one past the current max`() {
        val raised = DesktopPolicy.raise(TilePlacement(0.2f, 0.2f, 0), maxZ = 7)
        assertEquals(8, raised.z)
    }

    // ── snap (shortcut tiles) ─────────────────────────────────────────────────────────

    @Test
    fun `snap quantizes to the nearest grid cell center`() {
        // 8 columns over [0,1]: cell 1 spans from 0.125 up to but not including 0.25, center 0.1875.
        val snapped = DesktopPolicy.snap(TilePlacement(x = 0.20f, y = 0.5f, z = 0))
        assertEquals(0.1875f, snapped.x, 0.0001f)
    }

    @Test
    fun `snap is idempotent -- snapping an already-snapped placement changes nothing`() {
        val once = DesktopPolicy.snap(TilePlacement(x = 0.37f, y = 0.61f, z = 2))
        val twice = DesktopPolicy.snap(once)
        assertEquals(once, twice)
    }

    @Test
    fun `snap is deterministic for the same input`() {
        val a = DesktopPolicy.snap(TilePlacement(0.42f, 0.58f, 1))
        val b = DesktopPolicy.snap(TilePlacement(0.42f, 0.58f, 1))
        assertEquals(a, b)
    }

    @Test
    fun `snap preserves z`() {
        val snapped = DesktopPolicy.snap(TilePlacement(x = 0.4f, y = 0.4f, z = 9))
        assertEquals(9, snapped.z)
    }

    @Test
    fun `snap never lands outside the safe band, across a dense sweep of inputs`() {
        for (i in 0..40) {
            for (j in 0..40) {
                val p = DesktopPolicy.snap(TilePlacement(x = i / 40f, y = j / 40f, z = 0))
                assertEquals(p, DesktopPolicy.clamp(p))
            }
        }
    }

    // ── nextFreePlacement (shortcut tiles) ───────────────────────────────────────────

    @Test
    fun `nextFreePlacement on an empty desktop lands inside the safe band`() {
        val p = DesktopPolicy.nextFreePlacement(emptyList())
        assertEquals(p, DesktopPolicy.clamp(p))
    }

    @Test
    fun `nextFreePlacement is deterministic for the same existing set`() {
        val existing = listOf(TilePlacement(0.3f, 0.3f, 0))
        val a = DesktopPolicy.nextFreePlacement(existing)
        val b = DesktopPolicy.nextFreePlacement(existing)
        assertEquals(a, b)
    }

    @Test
    fun `nextFreePlacement avoids the immediate neighborhood of an occupied cell`() {
        val first = DesktopPolicy.nextFreePlacement(emptyList())
        val second = DesktopPolicy.nextFreePlacement(listOf(first))
        assertNotEquals(first.x to first.y, second.x to second.y)
        // Genuinely a different cell, not just a sub-pixel jitter off the same one.
        assertTrue(abs(first.x - second.x) > 0.01f || abs(first.y - second.y) > 0.01f)
    }

    @Test
    fun `nextFreePlacement fills the desktop without ever repeating a cell`() {
        // Comfortably within the safe band's non-adjacent grid capacity (roughly 6 cols x 11 rows
        // at 2-cell spacing) so every one of these resolves on the grid itself, not the fallback.
        val placements = mutableListOf<TilePlacement>()
        repeat(15) { placements += DesktopPolicy.nextFreePlacement(placements) }
        val distinctCells = placements.map { it.x to it.y }.toSet()
        assertEquals(placements.size, distinctCells.size)
    }

    @Test
    fun `nextFreePlacement falls back to the canvas cascade once the grid is saturated`() {
        // Cram far more occupied cells than the safe band's grid region can possibly hold so
        // every row-major scan cell is within one step of something -- the cascade must still
        // hand back a valid, in-band placement rather than throwing or looping forever.
        val jammed = (0 until 20).flatMap { row ->
            (0 until 20).map { col -> TilePlacement(x = col / 20f, y = row / 20f, z = 0) }
        }
        val fallback = DesktopPolicy.nextFreePlacement(jammed)
        assertEquals(fallback, DesktopPolicy.clamp(fallback))
    }

    // ── widgetWidthFraction -- the two-column grid, in real dp ───────────────────────

    @Test
    fun `widgetWidthFraction gives compact cards half the viewport, less margins and the gutter`() {
        val viewportWidthDp = 400f
        val expectedCompactDp = (viewportWidthDp - 2 * DesktopPolicy.OUTER_MARGIN_DP - DesktopPolicy.COLUMN_GUTTER_DP) / 2f
        val expected = expectedCompactDp / viewportWidthDp
        assertEquals(expected, DesktopPolicy.widgetWidthFraction(DesktopItemSize.SMALL, viewportWidthDp), 0.0001f)
        assertEquals(expected, DesktopPolicy.widgetWidthFraction(DesktopItemSize.MEDIUM, viewportWidthDp), 0.0001f)
    }

    @Test
    fun `widgetWidthFraction gives a LARGE card the full viewport less the outer margins`() {
        val viewportWidthDp = 400f
        val expected = (viewportWidthDp - 2 * DesktopPolicy.OUTER_MARGIN_DP) / viewportWidthDp
        assertEquals(expected, DesktopPolicy.widgetWidthFraction(DesktopItemSize.LARGE, viewportWidthDp), 0.0001f)
    }

    @Test
    fun `widgetWidthFraction re-derives from the live width -- it is not one fixed constant`() {
        // The Build-11 bug this whole section replaces: a WIDTH FRACTION fixed regardless of the
        // real screen, which drifted on any device other than the one it was tuned against.
        val narrow = DesktopPolicy.widgetWidthFraction(DesktopItemSize.LARGE, 360f)
        val wide = DesktopPolicy.widgetWidthFraction(DesktopItemSize.LARGE, 480f)
        assertNotEquals(narrow, wide)
    }

    // ── clampWidget / snapWidget -- width- AND viewport-aware ────────────────────────

    @Test
    fun `clampWidget keeps a full-width card fully on screen at the reference width`() {
        val width = DesktopPolicy.REFERENCE_VIEWPORT_WIDTH_DP
        val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
        val p = DesktopPolicy.clampWidget(TilePlacement(0.6f, 0.5f, 0), DesktopItemSize.LARGE, width, world)
        assertEquals(DesktopPolicy.leftColumnX(width), p.x, 0.0001f)
        assertTrue(p.x + DesktopPolicy.widgetWidthFraction(DesktopItemSize.LARGE, width) <= 1f)
    }

    @Test
    fun `clampWidget lets a compact card reach both columns but never off-screen`() {
        val width = DesktopPolicy.REFERENCE_VIEWPORT_WIDTH_DP
        val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
        val left = DesktopPolicy.clampWidget(TilePlacement(-0.2f, 0.5f, 0), DesktopItemSize.SMALL, width, world)
        val right = DesktopPolicy.clampWidget(TilePlacement(1.5f, 0.5f, 0), DesktopItemSize.SMALL, width, world)
        assertEquals(DesktopPolicy.leftColumnX(width), left.x, 0.0001f)
        assertTrue(right.x + DesktopPolicy.widgetWidthFraction(DesktopItemSize.SMALL, width) <= 1f)
    }

    @Test
    fun `clampWidget keeps a widget on screen across a sweep of real device widths`() {
        // The Build-11 bug this pins directly: clamp used to be computed against a fixed 360dp
        // baseline (CanvasLayoutPolicy.kt's own baseline, reused for widgets too), which pushed a
        // 92dp tile 28dp off the edge of a 411dp-wide screen. Every width below must hold on its
        // own terms now, not one baseline's.
        for (viewportWidthDp in listOf(360f, 390f, 411f, 440f, 480f)) {
            for (size in DesktopItemSize.entries) {
                val width = DesktopPolicy.widgetWidthFraction(size, viewportWidthDp)
                val p = DesktopPolicy.clampWidget(
                    TilePlacement(0.99f, 0.5f, 0),
                    size,
                    viewportWidthDp,
                    DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat(),
                )
                assertTrue("size=$size width=$viewportWidthDp: right edge ${p.x + width} must stay on screen", p.x + width <= 1.0001f)
                assertTrue("size=$size width=$viewportWidthDp: left edge ${p.x} must not be negative", p.x >= -0.0001f)
            }
        }
    }

    @Test
    fun `clampWidget keeps a widget within TOP_MARGIN_DP and BOTTOM_MARGIN_DP of the world's own edges`() {
        val world = 1000f
        val width = DesktopPolicy.REFERENCE_VIEWPORT_WIDTH_DP
        val tooHigh = DesktopPolicy.clampWidget(TilePlacement(0.5f, -1f, 0), DesktopItemSize.SMALL, width, world)
        val tooLow = DesktopPolicy.clampWidget(TilePlacement(0.5f, 2f, 0), DesktopItemSize.SMALL, width, world)
        assertEquals(DesktopPolicy.TOP_MARGIN_DP / world, tooHigh.y, 0.0001f)
        assertEquals(1f - DesktopPolicy.BOTTOM_MARGIN_DP / world, tooLow.y, 0.0001f)
    }

    @Test
    fun `snapWidget lands a compact card on exactly the left or right column, at any viewport width`() {
        for (viewportWidthDp in listOf(360f, 400f, 440f)) {
            val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
            val left = DesktopPolicy.snapWidget(TilePlacement(0.10f, 0.5f, 0), DesktopItemSize.SMALL, viewportWidthDp, world)
            val right = DesktopPolicy.snapWidget(TilePlacement(0.90f, 0.5f, 0), DesktopItemSize.SMALL, viewportWidthDp, world)
            assertEquals(DesktopPolicy.leftColumnX(viewportWidthDp), left.x, 0.0001f)
            assertEquals(DesktopPolicy.rightColumnX(viewportWidthDp), right.x, 0.0001f)
        }
    }

    @Test
    fun `snapWidget pins a full-width card to the left column no matter the drag`() {
        val width = DesktopPolicy.REFERENCE_VIEWPORT_WIDTH_DP
        val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
        val p = DesktopPolicy.snapWidget(TilePlacement(0.88f, 0.5f, 3), DesktopItemSize.LARGE, width, world)
        assertEquals(DesktopPolicy.leftColumnX(width), p.x, 0.0001f)
        assertEquals(3, p.z)
    }

    @Test
    fun `snapWidget quantizes y to the VERTICAL_QUANTUM_DP step of the real world height`() {
        val width = DesktopPolicy.REFERENCE_VIEWPORT_WIDTH_DP
        val world = 800f
        val p = DesktopPolicy.snapWidget(TilePlacement(0.5f, 0.503f, 0), DesktopItemSize.SMALL, width, world)
        val quantumFraction = DesktopPolicy.VERTICAL_QUANTUM_DP / world
        val steps = p.y / quantumFraction
        assertEquals(round(steps), steps, 0.01f)
    }

    @Test
    fun `snapWidget never lands outside clampWidget's own band, across a dense sweep`() {
        val width = DesktopPolicy.REFERENCE_VIEWPORT_WIDTH_DP
        val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
        for (i in 0..20) {
            for (j in 0..20) {
                val size = DesktopItemSize.entries[(i + j) % DesktopItemSize.entries.size]
                val p = DesktopPolicy.snapWidget(TilePlacement(i / 20f, j / 20f, 0), size, width, world)
                assertEquals(p, DesktopPolicy.clampWidget(p, size, width, world))
            }
        }
    }

    // ── defaultSeed -- the hand-laid two-column masonry ───────────────────────────────

    @Test
    fun `defaultSeed produces exactly the eight Build-10 widgets`() {
        val seed = DesktopPolicy.defaultSeed()
        assertEquals(8, seed.size)
        assertTrue(seed.all { it is DesktopItem.Widget })
        val types = seed.map { (it as DesktopItem.Widget).type }
        assertEquals(
            listOf(
                DesktopWidgetType.STORAGE,
                DesktopWidgetType.QUICK_ACCESS,
                DesktopWidgetType.RECYCLE_BIN,
                DesktopWidgetType.PINNED,
                DesktopWidgetType.TAGS,
                DesktopWidgetType.SHELF,
                DesktopWidgetType.RECENTS,
                DesktopWidgetType.QUICK_ACTIONS,
            ),
            types,
        )
    }

    @Test
    fun `defaultSeed ids are unique and stable-looking`() {
        val seed = DesktopPolicy.defaultSeed()
        assertEquals(seed.size, seed.map { it.id }.toSet().size)
        assertTrue(seed.all { it.id.startsWith("seed-") })
    }

    @Test
    fun `defaultSeed places the storage widget first, large, top of the layout`() {
        val seed = DesktopPolicy.defaultSeed()
        val storage = seed.first() as DesktopItem.Widget
        assertEquals(DesktopWidgetType.STORAGE, storage.type)
        assertEquals(DesktopItemSize.LARGE, storage.size)
    }

    @Test
    fun `defaultSeed's quick-access widget targets downloads`() {
        val seed = DesktopPolicy.defaultSeed()
        val quickAccess = seed.first { (it as DesktopItem.Widget).type == DesktopWidgetType.QUICK_ACCESS } as DesktopItem.Widget
        assertEquals("downloads", quickAccess.config["target"])
    }

    @Test
    fun `defaultSeed's recycle-bin size agrees with WidgetRegistry's own default -- the two can never drift apart again`() {
        val seed = DesktopPolicy.defaultSeed()
        val recycleBin = seed.first { (it as DesktopItem.Widget).type == DesktopWidgetType.RECYCLE_BIN } as DesktopItem.Widget
        assertEquals(WidgetRegistry.of(DesktopWidgetType.RECYCLE_BIN).defaultSize, recycleBin.size)
    }

    @Test
    fun `defaultSeed places every widget inside the widget clamp for its own width, at the reference viewport`() {
        // Widgets live under clampWidget, not the canvas safe band -- the band's own left margin
        // is what used to shove 94%-wide cards off the right edge of the screen. Compared
        // component-wise with a small tolerance, not struct equality: clampWidget's own maxX and
        // rightColumnX are only mathematically (not necessarily bit-for-bit) the same expression.
        val seed = DesktopPolicy.defaultSeed()
        val width = DesktopPolicy.REFERENCE_VIEWPORT_WIDTH_DP
        val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
        seed.forEach { item ->
            val widget = item as DesktopItem.Widget
            val clamped = DesktopPolicy.clampWidget(item.placement, widget.size, width, world)
            assertEquals(item.id, item.placement.x, clamped.x, 0.0005f)
            assertEquals(item.id, item.placement.y, clamped.y, 0.0005f)
        }
    }

    @Test
    fun `defaultSeed columns land exactly on the widget snap columns`() {
        val seed = DesktopPolicy.defaultSeed()
        val width = DesktopPolicy.REFERENCE_VIEWPORT_WIDTH_DP
        seed.forEach { item ->
            val widget = item as DesktopItem.Widget
            val expected = when {
                widget.size == DesktopItemSize.LARGE -> DesktopPolicy.leftColumnX(width)
                widget.placement.x < 0.5f -> DesktopPolicy.leftColumnX(width)
                else -> DesktopPolicy.rightColumnX(width)
            }
            assertEquals(expected, widget.placement.x, 0.0001f)
        }
    }

    @Test
    fun `defaultSeed's y values already sit on the VERTICAL_QUANTUM_DP grid`() {
        val seed = DesktopPolicy.defaultSeed()
        val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
        val quantumFraction = DesktopPolicy.VERTICAL_QUANTUM_DP / world
        seed.forEach { item ->
            val steps = item.placement.y / quantumFraction
            assertEquals(item.id, round(steps), steps, 0.01f)
        }
    }

    @Test
    fun `defaultSeed's vertical gutters are all exactly COLUMN_GUTTER_DP`() {
        val seed = DesktopPolicy.defaultSeed()
        val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
        fun topDp(id: String) = seed.first { it.id == id }.placement.y * world
        fun bottomDp(id: String): Float {
            val widget = seed.first { it.id == id } as DesktopItem.Widget
            return widget.placement.y * world + WidgetRegistry.of(widget.type).height.value
        }
        val gutter = DesktopPolicy.COLUMN_GUTTER_DP
        // Storage, then Deleted files -- both full width, stacked first.
        assertEquals(gutter, topDp("seed-recycle-bin") - bottomDp("seed-storage"), 0.5f)
        // Both columns resume together right after Deleted files.
        assertEquals(gutter, topDp("seed-quick-access") - bottomDp("seed-recycle-bin"), 0.5f)
        assertEquals(gutter, topDp("seed-recents") - bottomDp("seed-recycle-bin"), 0.5f)
        // Left column continues: Quick access -> Pinned -> Tags.
        assertEquals(gutter, topDp("seed-pinned") - bottomDp("seed-quick-access"), 0.5f)
        assertEquals(gutter, topDp("seed-tags") - bottomDp("seed-pinned"), 0.5f)
        // Right column continues: Recents -> Quick actions -> Shelf.
        assertEquals(gutter, topDp("seed-quick-actions") - bottomDp("seed-recents"), 0.5f)
        assertEquals(gutter, topDp("seed-shelf") - bottomDp("seed-quick-actions"), 0.5f)
    }

    @Test
    fun `WORLD_MIN_HEIGHT_DP is exactly the seed's own lowest edge plus BOTTOM_MARGIN_DP -- no extra dead scroll`() {
        val seed = DesktopPolicy.defaultSeed()
        val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
        val lowestBottomDp = seed.maxOf { item ->
            val widget = item as DesktopItem.Widget
            item.placement.y * world + WidgetRegistry.of(widget.type).height.value
        }
        assertEquals(DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat(), lowestBottomDp + DesktopPolicy.BOTTOM_MARGIN_DP, 0.5f)
    }

    @Test
    fun `defaultSeed is deterministic across calls -- no randomness`() {
        val first = DesktopPolicy.defaultSeed()
        val second = DesktopPolicy.defaultSeed()
        assertEquals(first, second)
    }

    @Test
    fun `defaultSeed cards never overlap at real card sizes, on the reference viewport`() {
        // The regression the Build-11 render pass first caught, re-checked here against the new
        // per-widget-TYPE content-fit heights (WidgetRegistry.height) rather than the old blanket
        // 152/216/360-by-size table.
        val viewportWidth = DesktopPolicy.REFERENCE_VIEWPORT_WIDTH_DP
        val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
        data class R(val l: Float, val t: Float, val r: Float, val b: Float)
        val rects = DesktopPolicy.defaultSeed().map { item ->
            val widget = item as DesktopItem.Widget
            val leftPx = widget.placement.x * viewportWidth
            val topPx = widget.placement.y * world
            val widthPx = DesktopPolicy.widgetWidthFraction(widget.size, viewportWidth) * viewportWidth
            val heightPx = WidgetRegistry.of(widget.type).height.value
            R(l = leftPx, t = topPx, r = leftPx + widthPx, b = topPx + heightPx)
        }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val a = rects[i]; val b = rects[j]
            val overlaps = a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b
            assertTrue("seed cards $i and $j overlap: $a vs $b", !overlaps)
        }
        // And the whole layout stays inside the world with a bottom margin to spare.
        assertTrue(rects.all { it.b <= world })
    }

    @Test
    fun `defaultSeed cards never overlap across a sweep of real device widths`() {
        data class R(val l: Float, val t: Float, val r: Float, val b: Float)
        for (viewportWidth in listOf(360f, 390f, 411f, 440f, 480f)) {
            val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
            val rects = DesktopPolicy.defaultSeed(viewportWidth).map { item ->
                val widget = item as DesktopItem.Widget
                val leftPx = widget.placement.x * viewportWidth
                val topPx = widget.placement.y * world
                val widthPx = DesktopPolicy.widgetWidthFraction(widget.size, viewportWidth) * viewportWidth
                val heightPx = WidgetRegistry.of(widget.type).height.value
                R(l = leftPx, t = topPx, r = leftPx + widthPx, b = topPx + heightPx)
            }
            for (i in rects.indices) for (j in i + 1 until rects.size) {
                val a = rects[i]; val b = rects[j]
                val overlaps = a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b
                assertTrue("width=$viewportWidth: seed cards $i and $j overlap: $a vs $b", !overlaps)
            }
            assertTrue("width=$viewportWidth: no card may spill left of the viewport", rects.all { it.l >= -0.01f })
            assertTrue("width=$viewportWidth: no card may spill right of the viewport", rects.all { it.r <= viewportWidth + 0.01f })
        }
    }
}
