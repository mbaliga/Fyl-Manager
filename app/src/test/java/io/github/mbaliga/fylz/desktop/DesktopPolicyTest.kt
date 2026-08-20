package io.github.mbaliga.fylz.desktop

import io.github.mbaliga.fylz.canvas.CanvasLayoutPolicy
import io.github.mbaliga.fylz.canvas.TilePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM -- every function under test is a pure function of [TilePlacement] and primitives, no
 * Uri/Context involved, unlike [CanvasLayoutPolicyTest] which needs Robolectric only for the
 * [io.github.mbaliga.fylz.model.FileEntry] fixtures its own [CanvasLayoutPolicy] functions take.
 */
class DesktopPolicyTest {

    // ── clamp / raise (delegate to CanvasLayoutPolicy) ──────────────────────────────────

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

    // ── snap ──────────────────────────────────────────────────────────────────────────

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

    // ── nextFreePlacement ─────────────────────────────────────────────────────────────

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
        assertTrue(kotlin.math.abs(first.x - second.x) > 0.01f || kotlin.math.abs(first.y - second.y) > 0.01f)
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

    // ── defaultSeed ───────────────────────────────────────────────────────────────────

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
    fun `defaultSeed places every widget inside the widget clamp for its own width`() {
        // Widgets live under clampWidget, not the canvas safe band -- the band's 0.16 left margin
        // is what used to shove 94%-wide cards off the right edge of the screen.
        val seed = DesktopPolicy.defaultSeed()
        seed.forEach { item ->
            val widget = item as DesktopItem.Widget
            val width = DesktopPolicy.widgetWidthFraction(widget.size)
            assertEquals(item.placement, DesktopPolicy.clampWidget(item.placement, width))
        }
    }

    @Test
    fun `defaultSeed columns land exactly on the widget snap columns`() {
        val seed = DesktopPolicy.defaultSeed()
        seed.forEach { item ->
            val widget = item as DesktopItem.Widget
            val expected =
                if (DesktopPolicy.widgetWidthFraction(widget.size) >= DesktopPolicy.WIDGET_WIDTH_FULL) 0.03f
                else if (widget.placement.x < 0.25f) 0.03f
                else DesktopPolicy.WIDGET_RIGHT_COLUMN_X
            assertEquals(expected, widget.placement.x, 0.0001f)
        }
    }

    @Test
    fun `defaultSeed is deterministic across calls -- no randomness`() {
        val first = DesktopPolicy.defaultSeed()
        val second = DesktopPolicy.defaultSeed()
        assertEquals(first, second)
    }

    // ── clampWidget / snapWidget (width-aware widget geometry) ────────────────────────

    @Test
    fun `clampWidget keeps a full-width card fully on screen`() {
        val p = DesktopPolicy.clampWidget(TilePlacement(0.6f, 0.5f, 0), DesktopPolicy.WIDGET_WIDTH_FULL)
        assertEquals(0.03f, p.x, 0.0001f)
        assertTrue(p.x + DesktopPolicy.WIDGET_WIDTH_FULL <= 0.98f)
    }

    @Test
    fun `clampWidget lets a compact card reach both columns but never off-screen`() {
        val w = DesktopPolicy.WIDGET_WIDTH_COMPACT
        val left = DesktopPolicy.clampWidget(TilePlacement(-0.2f, 0.5f, 0), w)
        val right = DesktopPolicy.clampWidget(TilePlacement(0.95f, 0.5f, 0), w)
        assertEquals(0.03f, left.x, 0.0001f)
        assertTrue(right.x + w <= 0.98f)
    }

    @Test
    fun `snapWidget lands a compact card on exactly the left or right column`() {
        val w = DesktopPolicy.WIDGET_WIDTH_COMPACT
        val left = DesktopPolicy.snapWidget(TilePlacement(0.20f, 0.5f, 0), w)
        val right = DesktopPolicy.snapWidget(TilePlacement(0.40f, 0.5f, 0), w)
        assertEquals(0.03f, left.x, 0.0001f)
        assertEquals(DesktopPolicy.WIDGET_RIGHT_COLUMN_X, right.x, 0.0001f)
    }

    @Test
    fun `snapWidget pins a full-width card to the left gutter no matter the drag`() {
        val p = DesktopPolicy.snapWidget(TilePlacement(0.88f, 0.5f, 3), DesktopPolicy.WIDGET_WIDTH_FULL)
        assertEquals(0.03f, p.x, 0.0001f)
        assertEquals(3, p.z)
    }

    @Test
    fun `defaultSeed cards never overlap at real card sizes on the minimum world`() {
        // The regression the Build-11 render pass caught: cell-spaced placements stacked
        // 46-94%-wide cards into one mass. Re-derive every card's dp rectangle exactly the way
        // DesktopTile does (widths from widgetWidthFraction, heights 152/216/360) on a 440dp-wide
        // viewport over the WORLD_MIN_HEIGHT_DP world, and require pairwise disjointness.
        val viewportWidth = 440f
        val world = DesktopPolicy.WORLD_MIN_HEIGHT_DP.toFloat()
        fun heightDp(size: DesktopItemSize) = when (size) {
            DesktopItemSize.SMALL -> 152f
            DesktopItemSize.MEDIUM -> 216f
            DesktopItemSize.LARGE -> 360f
        }
        data class R(val l: Float, val t: Float, val r: Float, val b: Float)
        val rects = DesktopPolicy.defaultSeed().map { item ->
            val widget = item as DesktopItem.Widget
            val leftPx = widget.placement.x * viewportWidth
            val topPx = widget.placement.y * world
            R(
                l = leftPx,
                t = topPx,
                r = leftPx + DesktopPolicy.widgetWidthFraction(widget.size) * viewportWidth,
                b = topPx + heightDp(widget.size),
            )
        }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val a = rects[i]; val b = rects[j]
            val overlaps = a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b
            assertTrue("seed cards $i and $j overlap: $a vs $b", !overlaps)
        }
        // And the whole layout stays inside the world with a bottom margin to spare.
        assertTrue(rects.all { it.b <= world })
    }
}
