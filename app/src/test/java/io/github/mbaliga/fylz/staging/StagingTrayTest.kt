package io.github.mbaliga.fylz.staging

import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// StagedItem carries a real Uri, which the plain-JVM stub cannot construct (same reason
// PdfPagePlanPolicyTest opts in); sdk pinned to the module's targetSdk like the rest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StagingTrayTest {

    private fun item(name: String) = StagedItem(
        uri = Uri.parse("content://fylz.test/tree/root/document/$name"),
        displayName = name,
        kind = EntryKind.TEXT,
    )

    @Test
    fun `staging keeps arrival order with new items at the end`() {
        val tray = StagingTray(TrayKind.CLIPBOARD)
            .stage(listOf(item("a.txt"), item("b.txt")))
            .stage(listOf(item("c.txt")))

        assertEquals(listOf("a.txt", "b.txt", "c.txt"), tray.items.map(StagedItem::displayName))
    }

    @Test
    fun `restaging an aboard document does not duplicate it`() {
        // A paste would otherwise copy one file twice and the looped browse would show
        // phantom copies of it.
        val tray = StagingTray(TrayKind.MOVE)
            .stage(listOf(item("a.txt"), item("b.txt")))
            .stage(listOf(item("b.txt"), item("c.txt")))

        assertEquals(listOf("a.txt", "b.txt", "c.txt"), tray.items.map(StagedItem::displayName))
    }

    @Test
    fun `staging nothing new returns the same tray instance`() {
        val tray = StagingTray(TrayKind.CLIPBOARD).stage(listOf(item("a.txt")))
        assertSame(tray, tray.stage(listOf(item("a.txt"))))
        assertSame(tray, tray.stage(emptyList()))
    }

    @Test
    fun `pulling one item out leaves the rest in order`() {
        val b = item("b.txt")
        val tray = StagingTray(TrayKind.CLIPBOARD)
            .stage(listOf(item("a.txt"), b, item("c.txt")))
            .without(b.uri)

        assertEquals(listOf("a.txt", "c.txt"), tray.items.map(StagedItem::displayName))
    }

    @Test
    fun `removing an unknown document is a no-op`() {
        val tray = StagingTray(TrayKind.CLIPBOARD).stage(listOf(item("a.txt")))
        assertSame(tray, tray.without(item("ghost.txt").uri))
    }

    @Test
    fun `clearing empties the tray`() {
        val tray = StagingTray(TrayKind.MOVE).stage(listOf(item("a.txt"))).clear()
        assertTrue(tray.isEmpty)
    }
}
