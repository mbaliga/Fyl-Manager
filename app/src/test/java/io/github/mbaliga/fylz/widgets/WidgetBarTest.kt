package io.github.mbaliga.fylz.widgets

import io.github.mbaliga.fylz.storage.StorageKind
import io.github.mbaliga.fylz.storage.StorageUsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM -- [WidgetBar.segments] is a pure function of [StorageUsageSnapshot] and primitives,
 * no Uri/Context involved, [DesktopPolicyTest][io.github.mbaliga.fylz.desktop.DesktopPolicyTest]-
 * modelled.
 */
class WidgetBarTest {

    // ── empty snapshot ────────────────────────────────────────────────────────────────

    @Test
    fun `a null snapshot -- never scanned -- yields no segments`() {
        assertTrue(WidgetBar.segments(null).isEmpty())
    }

    @Test
    fun `a snapshot with no bytes anywhere yields no segments`() {
        val snapshot = StorageUsageSnapshot(scannedAtMillis = 1_000L, kindBytes = emptyMap())
        assertTrue(WidgetBar.segments(snapshot).isEmpty())
    }

    // ── single kind ───────────────────────────────────────────────────────────────────

    @Test
    fun `a single kind fills the whole bar`() {
        val snapshot = StorageUsageSnapshot(
            scannedAtMillis = 1_000L,
            kindBytes = mapOf(StorageKind.PHOTOS to 500L),
        )

        val segments = WidgetBar.segments(snapshot)

        assertEquals(1, segments.size)
        assertEquals(StorageKind.PHOTOS, segments.single().kind)
        assertEquals(1f, segments.single().weight, 0.0001f)
        assertEquals(WidgetBar.colorFor(StorageKind.PHOTOS), segments.single().colorArgb)
    }

    @Test
    fun `a kind with zero bytes is omitted, not rendered as a zero-width segment`() {
        val snapshot = StorageUsageSnapshot(
            scannedAtMillis = 1_000L,
            kindBytes = mapOf(StorageKind.PHOTOS to 500L, StorageKind.OTHER to 0L),
        )

        val segments = WidgetBar.segments(snapshot)

        assertEquals(1, segments.size)
        assertEquals(StorageKind.PHOTOS, segments.single().kind)
    }

    // ── rounding / ordering across several kinds ────────────────────────────────────────

    @Test
    fun `weights across several kinds sum to 1 within floating-point rounding`() {
        val snapshot = StorageUsageSnapshot(
            scannedAtMillis = 1_000L,
            kindBytes = mapOf(
                StorageKind.PHOTOS to 1L,
                StorageKind.DOCUMENTS to 1L,
                StorageKind.VIDEOS to 1L,
            ),
        )

        val segments = WidgetBar.segments(snapshot)

        assertEquals(3, segments.size)
        val total = segments.sumOf { it.weight.toDouble() }
        assertEquals(1.0, total, 0.0001)
    }

    @Test
    fun `an uneven split still sums to 1 within rounding`() {
        val snapshot = StorageUsageSnapshot(
            scannedAtMillis = 1_000L,
            kindBytes = mapOf(StorageKind.PHOTOS to 1L, StorageKind.DOCUMENTS to 2L, StorageKind.VIDEOS to 7L),
        )

        val segments = WidgetBar.segments(snapshot)

        assertEquals(0.1f, segments.first { it.kind == StorageKind.PHOTOS }.weight, 0.0001f)
        assertEquals(0.7f, segments.first { it.kind == StorageKind.VIDEOS }.weight, 0.0001f)
        assertEquals(1.0, segments.sumOf { it.weight.toDouble() }, 0.0001)
    }

    @Test
    fun `segment order follows STORAGE_KIND_ORDER, not map iteration order`() {
        val snapshot = StorageUsageSnapshot(
            scannedAtMillis = 1_000L,
            kindBytes = mapOf(StorageKind.ARCHIVES to 10L, StorageKind.PHOTOS to 10L, StorageKind.OTHER to 10L),
        )

        val segments = WidgetBar.segments(snapshot)

        assertEquals(listOf(StorageKind.PHOTOS, StorageKind.ARCHIVES, StorageKind.OTHER), segments.map { it.kind })
    }

    // ── layoutSegments -- Build 11.5's density-scaled, gapped, rounded-end bar ─────────────

    @Test
    fun `no segments lays out to nothing, regardless of track width`() {
        assertTrue(WidgetBar.layoutSegments(emptyList(), trackWidthPx = 600f, gapPx = 2f).isEmpty())
    }

    @Test
    fun `a non-positive track width lays out to nothing`() {
        val segments = listOf(BarSegment(StorageKind.PHOTOS, 0xFF000000.toInt(), 1f))
        assertTrue(WidgetBar.layoutSegments(segments, trackWidthPx = 0f, gapPx = 2f).isEmpty())
        assertTrue(WidgetBar.layoutSegments(segments, trackWidthPx = -10f, gapPx = 2f).isEmpty())
    }

    @Test
    fun `a single segment fills the whole track with no gap reserved`() {
        val segments = listOf(BarSegment(StorageKind.PHOTOS, 0xFF000000.toInt(), 1f))

        val laid = WidgetBar.layoutSegments(segments, trackWidthPx = 600f, gapPx = 2f)

        assertEquals(1, laid.size)
        assertEquals(0f, laid.single().startPx, 0.001f)
        assertEquals(600f, laid.single().endPx, 0.001f)
    }

    @Test
    fun `two even segments are separated by exactly gapPx and the run still fills the track`() {
        val segments = listOf(
            BarSegment(StorageKind.PHOTOS, 0xFF000000.toInt(), 0.5f),
            BarSegment(StorageKind.DOCUMENTS, 0xFF000001.toInt(), 0.5f),
        )

        val laid = WidgetBar.layoutSegments(segments, trackWidthPx = 100f, gapPx = 2f)

        assertEquals(2, laid.size)
        val (first, second) = laid
        assertEquals(0f, first.startPx, 0.001f)
        assertEquals(2f, second.startPx - first.endPx, 0.001f) // the gap, exactly
        assertEquals(100f, second.endPx, 0.001f) // last run always reaches the track's own end
    }

    @Test
    fun `the last segment absorbs rounding drift so the run still reaches the track's end`() {
        val segments = listOf(
            BarSegment(StorageKind.PHOTOS, 0xFF000000.toInt(), 1f / 3f),
            BarSegment(StorageKind.DOCUMENTS, 0xFF000001.toInt(), 1f / 3f),
            BarSegment(StorageKind.VIDEOS, 0xFF000002.toInt(), 1f / 3f),
        )

        val laid = WidgetBar.layoutSegments(segments, trackWidthPx = 100f, gapPx = 2f)

        assertEquals(100f, laid.last().endPx, 0.001f)
        // every gap between adjacent runs is exactly gapPx, not just the first one
        laid.zipWithNext().forEach { (a, b) -> assertEquals(2f, b.startPx - a.endPx, 0.001f) }
    }

    @Test
    fun `segment order is preserved -- the bar draws left to right in segments() order`() {
        val segments = listOf(
            BarSegment(StorageKind.PHOTOS, 0xFF000000.toInt(), 0.25f),
            BarSegment(StorageKind.DOCUMENTS, 0xFF000001.toInt(), 0.75f),
        )

        val laid = WidgetBar.layoutSegments(segments, trackWidthPx = 200f, gapPx = 2f)

        assertEquals(listOf(StorageKind.PHOTOS, StorageKind.DOCUMENTS), laid.map { it.kind })
        assertTrue(laid[0].startPx < laid[1].startPx)
    }

    @Test
    fun `gaps that would outweigh the track never go negative -- they just eat the whole track`() {
        val segments = listOf(
            BarSegment(StorageKind.PHOTOS, 0xFF000000.toInt(), 0.5f),
            BarSegment(StorageKind.DOCUMENTS, 0xFF000001.toInt(), 0.5f),
        )

        val laid = WidgetBar.layoutSegments(segments, trackWidthPx = 1f, gapPx = 50f)

        laid.forEach { run ->
            assertTrue(run.startPx >= 0f)
            assertTrue(run.endPx >= run.startPx)
        }
    }

    @Test
    fun `GAP_DP is the Build 11-5 fidelity spec's 2dp`() {
        assertEquals(2, WidgetBar.GAP_DP)
    }

    // ── outerRadiusPx -- rounded outer ends, half the bar's own height ─────────────────────

    @Test
    fun `outer radius is exactly half the track height`() {
        assertEquals(10f, WidgetBar.outerRadiusPx(20f), 0.001f)
        assertEquals(0f, WidgetBar.outerRadiusPx(0f), 0.001f)
    }
}
