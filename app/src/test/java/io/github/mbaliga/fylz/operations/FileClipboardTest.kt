package io.github.mbaliga.fylz.operations

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileClipboardTest {

    private fun item(name: String, id: String, parent: String?, dir: Boolean = false) =
        ClipItem(Uri.parse("content://t/$id"), name, dir, id, parent)

    private fun clip(mode: ClipboardMode, vararg items: ClipItem) = FileClipboard(mode, items.toList())

    @Test
    fun `a copy into another folder transfers everything`() {
        val plan = ClipboardPolicy.plan(
            clip(ClipboardMode.COPY, item("a.txt", "p:A/a.txt", "p:A"), item("b.txt", "p:A/b.txt", "p:A")),
            "p:B",
        )
        assertEquals(2, plan.transfer.size)
        assertTrue(plan.alreadyHere.isEmpty() && plan.blocked.isEmpty())
    }

    @Test
    fun `copying into the same folder is allowed so it can duplicate`() {
        val plan = ClipboardPolicy.plan(clip(ClipboardMode.COPY, item("a.txt", "p:A/a.txt", "p:A")), "p:A")
        assertEquals(1, plan.transfer.size)
        assertTrue(plan.alreadyHere.isEmpty())
    }

    @Test
    fun `cutting into the same folder does nothing`() {
        val plan = ClipboardPolicy.plan(clip(ClipboardMode.CUT, item("a.txt", "p:A/a.txt", "p:A")), "p:A")
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.alreadyHere.size)
    }

    @Test
    fun `a cut mixes moved and already-here items`() {
        val plan = ClipboardPolicy.plan(
            clip(ClipboardMode.CUT, item("a", "p:A/a", "p:A"), item("c", "p:C/c", "p:C")),
            "p:A",
        )
        assertEquals(listOf("c"), plan.transfer.map { it.name })
        assertEquals(listOf("a"), plan.alreadyHere.map { it.name })
    }

    @Test
    fun `an unknown source folder is never a same-folder no-op`() {
        val plan = ClipboardPolicy.plan(clip(ClipboardMode.CUT, item("a", "p:A/a", null)), "p:A")
        assertEquals(1, plan.transfer.size)
    }

    @Test
    fun `a folder cannot be pasted into itself`() {
        for (mode in ClipboardMode.entries) {
            val plan = ClipboardPolicy.plan(clip(mode, item("Photos", "p:Photos", "p:", dir = true)), "p:Photos")
            assertTrue(plan.isEmpty)
            assertEquals(1, plan.blocked.size)
        }
    }

    @Test
    fun `a folder cannot be pasted into a descendant`() {
        val plan = ClipboardPolicy.plan(
            clip(ClipboardMode.COPY, item("Photos", "p:Photos", "p:", dir = true)),
            "p:Photos/2024/trip",
        )
        assertEquals(1, plan.blocked.size)
    }

    @Test
    fun `a sibling with a shared name prefix is not a descendant`() {
        assertFalse(ClipboardPolicy.isInside("p:Photos2", "p:Photos"))
        assertFalse(ClipboardPolicy.isInside("p:Photo", "p:Photos"))
        assertTrue(ClipboardPolicy.isInside("p:Photos/x", "p:Photos"))
        assertTrue(ClipboardPolicy.isInside("p:Photos", "p:Photos"))
    }

    @Test
    fun `a file is never blocked by the self-paste rule`() {
        val plan = ClipboardPolicy.plan(clip(ClipboardMode.COPY, item("a", "p:A", "p:", dir = false)), "p:A")
        assertEquals(1, plan.transfer.size)
        assertTrue(plan.blocked.isEmpty())
    }

    @Test
    fun `labels name a single item and count several`() {
        assertEquals("a.txt", clip(ClipboardMode.COPY, item("a.txt", "p:a", "p:")).label)
        assertEquals("2 items", clip(ClipboardMode.COPY, item("a", "p:a", "p:"), item("b", "p:b", "p:")).label)
    }

    @Test
    fun `an empty clipboard is rejected`() {
        val failure = runCatching { FileClipboard(ClipboardMode.COPY, emptyList()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `result messages say what happened`() {
        assertEquals("Pasted 1 item", ClipboardPolicy.resultMessage(ClipboardMode.COPY, 1, 0))
        assertEquals("Moved 3 items", ClipboardPolicy.resultMessage(ClipboardMode.CUT, 3, 0))
        assertEquals("Moved 2 items (1 already here)", ClipboardPolicy.resultMessage(ClipboardMode.CUT, 2, 1))
    }

    @Test
    fun `pdf file names carry the timestamp and no template residue`() {
        assertEquals("Fylz-pages-123.pdf", PdfOutputNames.pages(123))
        assertEquals("Fylz-merged-123.pdf", PdfOutputNames.merged(123))
        assertFalse(PdfOutputNames.pages(1).contains('$'))
    }
}
