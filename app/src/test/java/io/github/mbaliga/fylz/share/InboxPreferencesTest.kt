package io.github.mbaliga.fylz.share

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [InboxPreferences] is [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]-modelled: round trip,
 * plus the corrupted-current-falls-back-to-backup recovery that model pins for its own store
 * (also pinned by [io.github.mbaliga.fylz.history.RecentOpensStoreTest] for its sibling).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InboxPreferencesTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = InboxPreferences(context)
    private fun uri(tail: String) = Uri.parse("content://fylz.test/tree/$tail")

    // ── round trip ────────────────────────────────────────────────────────────────────

    @Test
    fun `a fresh store reports no location, not a crash`() {
        assertNull(store().location())
    }

    @Test
    fun `setLocation survives a fresh store instance over the same preferences`() {
        val location = InboxLocation(uri("tree"), uri("tree/document/tree"), "Inbox")

        store().setLocation(location)

        assertEquals(location, store().location())
    }

    @Test
    fun `setLocation overwrites a previously configured location`() {
        store().setLocation(InboxLocation(uri("a"), uri("a-doc"), "A"))
        val replacement = InboxLocation(uri("b"), uri("b-doc"), "B")

        store().setLocation(replacement)

        assertEquals(replacement, store().location())
    }

    @Test
    fun `clear removes the configured location`() {
        store().setLocation(InboxLocation(uri("a"), uri("a-doc"), "A"))

        store().clear()

        assertNull(store().location())
    }

    // ── corruption recovery ──────────────────────────────────────────────────────────

    @Test
    fun `a corrupted current value falls back to the last known good backup`() {
        val store = store()
        store.setLocation(InboxLocation(uri("a"), uri("a-doc"), "A"))
        // This second write is what promotes the first write's payload into the backup slot.
        store.setLocation(InboxLocation(uri("b"), uri("b-doc"), "B"))

        context.getSharedPreferences("fylz_inbox", Context.MODE_PRIVATE)
            .edit()
            .putString("location", "{not json[")
            .commit()

        assertEquals(InboxLocation(uri("a"), uri("a-doc"), "A"), store().location())
    }

    @Test
    fun `a malformed backup does not crash -- just leaves no location`() {
        context.getSharedPreferences("fylz_inbox", Context.MODE_PRIVATE)
            .edit()
            .putString("location", "{not json[")
            .putString("location:backup", "{also not json[")
            .commit()

        assertNull(store().location())
    }

    @Test
    fun `an empty preferences value decodes as no location, not a crash`() {
        context.getSharedPreferences("fylz_inbox", Context.MODE_PRIVATE)
            .edit()
            .putString("location", "")
            .commit()

        assertNull(store().location())
    }

    @Test
    fun `clear leaves the store usable for a fresh setLocation afterwards`() {
        val store = store()
        store.setLocation(InboxLocation(uri("a"), uri("a-doc"), "A"))
        store.clear()

        val fresh = InboxLocation(uri("c"), uri("c-doc"), "C")
        store.setLocation(fresh)

        assertEquals(fresh, store().location())
        assertTrue(store().location() != null)
    }
}
