package io.github.mbaliga.fylz.canvas

import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric only because [FileEntry] is keyed by a real `android.net.Uri`, which the plain
 * android.jar stub cannot construct (same reason `SortSpecTest` opts in) -- every function under
 * test here is otherwise a pure function of its arguments, no Context involved.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasLayoutPolicyTest {

    private fun entry(
        name: String,
        directory: Boolean = false,
        kind: EntryKind = EntryKind.TEXT,
        modified: Long? = 1_000,
    ) = FileEntry(
        uri = Uri.parse("content://test/${name.replace(' ', '_')}"),
        name = name,
        mimeType = "",
        sizeBytes = null,
        lastModifiedMillis = modified,
        flags = 0,
        kind = if (directory) EntryKind.DIRECTORY else kind,
    )

    // ── clamp ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `clamp leaves an already in-band placement untouched`() {
        val p = TilePlacement(x = 0.5f, y = 0.5f, z = 3)
        assertEquals(p, CanvasLayoutPolicy.clamp(p))
    }

    @Test
    fun `clamp pulls an out-of-band placement back to the nearest edge`() {
        val clamped = CanvasLayoutPolicy.clamp(TilePlacement(x = -5f, y = 5f, z = 1))
        assertTrue(clamped.x in 0f..1f)
        assertTrue(clamped.y in 0f..1f)
        assertTrue(clamped.x > 0f) // pulled inside the edge band, not just inside [0,1]
        assertTrue(clamped.y < 1f)
    }

    @Test
    fun `clamp never touches z`() {
        val clamped = CanvasLayoutPolicy.clamp(TilePlacement(x = -5f, y = -5f, z = 42))
        assertEquals(42, clamped.z)
    }

    // ── raise ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `raise puts a tile one past the current max`() {
        val raised = CanvasLayoutPolicy.raise(TilePlacement(0.2f, 0.2f, 0), maxZ = 7)
        assertEquals(8, raised.z)
    }

    @Test
    fun `raise leaves x and y untouched`() {
        val raised = CanvasLayoutPolicy.raise(TilePlacement(0.33f, 0.44f, 0), maxZ = 1)
        assertEquals(0.33f, raised.x, 0.0001f)
        assertEquals(0.44f, raised.y, 0.0001f)
    }

    // ── defaultPlacement ─────────────────────────────────────────────────────────────

    @Test
    fun `defaultPlacement is deterministic for the same index`() {
        val first = CanvasLayoutPolicy.defaultPlacement(4)
        val second = CanvasLayoutPolicy.defaultPlacement(4)
        assertEquals(first, second)
    }

    @Test
    fun `defaultPlacement never lands off the viewport, across a long run of indices`() {
        for (index in 0 until 200) {
            val p = CanvasLayoutPolicy.defaultPlacement(index)
            assertTrue("index $index x=${p.x}", p.x in 0f..1f)
            assertTrue("index $index y=${p.y}", p.y in 0f..1f)
        }
    }

    @Test
    fun `defaultPlacement stays clear of the edge bands, not just the raw viewport`() {
        // A clamped placement is idempotent under clamp; anything the cascade hands back that
        // still needed pulling in would fail this.
        val p = CanvasLayoutPolicy.defaultPlacement(11)
        assertEquals(p, CanvasLayoutPolicy.clamp(p))
    }

    @Test
    fun `defaultPlacement varies with index rather than collapsing to one spot`() {
        val positions = (0 until 6).map { CanvasLayoutPolicy.defaultPlacement(it) }
        assertTrue(positions.toSet().size > 1)
    }

    @Test
    fun `defaultPlacement z tracks the index`() {
        assertEquals(5, CanvasLayoutPolicy.defaultPlacement(5).z)
    }

    @Test
    fun `defaultPlacement position ignores what existing placements contain, only how many`() {
        val a = CanvasLayoutPolicy.defaultPlacement(2, existing = listOf(TilePlacement(0.9f, 0.9f, 9)))
        val b = CanvasLayoutPolicy.defaultPlacement(2, existing = listOf(TilePlacement(0.05f, 0.05f, 1)))
        // Same index, same COUNT of existing (one entry each, at wildly different spots) --
        // position depends on how many existing entries there are, never their actual values.
        assertEquals(a.x, b.x, 0.0001f)
        assertEquals(a.y, b.y, 0.0001f)
    }

    @Test
    fun `defaultPlacement advances its cascade step as existing grows, for the same index`() {
        val fresh = CanvasLayoutPolicy.defaultPlacement(0, existing = emptyList())
        val crowded = CanvasLayoutPolicy.defaultPlacement(0, existing = List(3) { TilePlacement(0f, 0f, it) })
        assertNotEquals(fresh.x to fresh.y, crowded.x to crowded.y)
    }

    // ── visibleSubset ─────────────────────────────────────────────────────────────────

    @Test
    fun `visibleSubset puts every directory ahead of every file`() {
        val entries = listOf(
            entry("z.txt", modified = 5_000),
            entry("Alpha", directory = true),
            entry("a.txt", modified = 9_000),
        )
        val subset = CanvasLayoutPolicy.visibleSubset(entries)
        assertEquals(listOf("Alpha", "a.txt", "z.txt"), subset.map { it.name })
    }

    @Test
    fun `visibleSubset orders files by most recently modified first`() {
        val entries = listOf(
            entry("old.txt", modified = 1_000),
            entry("new.txt", modified = 9_000),
            entry("mid.txt", modified = 5_000),
        )
        val subset = CanvasLayoutPolicy.visibleSubset(entries)
        assertEquals(listOf("new.txt", "mid.txt", "old.txt"), subset.map { it.name })
    }

    @Test
    fun `visibleSubset treats a null modified time as the oldest, not the newest`() {
        val entries = listOf(
            entry("unknown.txt", modified = null),
            entry("known.txt", modified = 1_000),
        )
        val subset = CanvasLayoutPolicy.visibleSubset(entries)
        assertEquals(listOf("known.txt", "unknown.txt"), subset.map { it.name })
    }

    @Test
    fun `visibleSubset caps the total and keeps directories over files when it must choose`() {
        val dirs = (1..5).map { entry("dir$it", directory = true) }
        val files = (1..5).map { entry("file$it.txt", modified = it.toLong()) }
        val subset = CanvasLayoutPolicy.visibleSubset(dirs + files, cap = 3)
        assertEquals(3, subset.size)
        assertTrue(subset.all { it.isDirectory })
    }

    @Test
    fun `visibleSubset defaults its cap to MAX_TILES`() {
        val entries = (1..60).map { entry("file$it.txt", modified = it.toLong()) }
        assertEquals(CanvasLayoutPolicy.MAX_TILES, CanvasLayoutPolicy.visibleSubset(entries).size)
    }

    @Test
    fun `visibleSubset is stable among files that tie on modified time`() {
        val entries = listOf(
            entry("a.txt", modified = 1_000),
            entry("b.txt", modified = 1_000),
            entry("c.txt", modified = 1_000),
        )
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), CanvasLayoutPolicy.visibleSubset(entries).map { it.name })
    }

    // ── bentoSpans ────────────────────────────────────────────────────────────────────

    @Test
    fun `bentoSpans gives every directory a 2x2 span`() {
        val entries = listOf(entry("Docs", directory = true), entry("note.txt", modified = 1_000))
        val spans = CanvasLayoutPolicy.bentoSpans(entries)
        assertEquals(2, spans[0])
    }

    @Test
    fun `bentoSpans gives only the two most recent media files a 2x2 span`() {
        val entries = listOf(
            entry("newest.jpg", kind = EntryKind.IMAGE, modified = 3_000),
            entry("middle.jpg", kind = EntryKind.IMAGE, modified = 2_000),
            entry("oldest.jpg", kind = EntryKind.IMAGE, modified = 1_000),
        )
        val spans = CanvasLayoutPolicy.bentoSpans(entries)
        assertEquals(listOf(2, 2, 1), spans)
    }

    @Test
    fun `bentoSpans never promotes a non-media file no matter how recent`() {
        val entries = listOf(
            entry("brand-new.txt", kind = EntryKind.TEXT, modified = 9_999),
            entry("old.jpg", kind = EntryKind.IMAGE, modified = 1),
        )
        val spans = CanvasLayoutPolicy.bentoSpans(entries)
        assertEquals(listOf(1, 2), spans)
    }

    @Test
    fun `bentoSpans treats video as media alongside image`() {
        val entries = listOf(
            entry("clip.mp4", kind = EntryKind.VIDEO, modified = 2_000),
            entry("photo.jpg", kind = EntryKind.IMAGE, modified = 1_000),
            entry("doc.pdf", kind = EntryKind.PDF, modified = 3_000),
        )
        val spans = CanvasLayoutPolicy.bentoSpans(entries)
        assertEquals(listOf(2, 2, 1), spans)
    }

    @Test
    fun `bentoSpans is aligned index for index with its input`() {
        val entries = (1..7).map { entry("file$it.txt", modified = it.toLong()) }
        assertEquals(entries.size, CanvasLayoutPolicy.bentoSpans(entries).size)
    }
}
