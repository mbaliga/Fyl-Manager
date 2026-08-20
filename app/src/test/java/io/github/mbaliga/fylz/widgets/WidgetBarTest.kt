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
}
