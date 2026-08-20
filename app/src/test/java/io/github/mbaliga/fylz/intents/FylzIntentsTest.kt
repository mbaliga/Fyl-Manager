package io.github.mbaliga.fylz.intents

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [FylzIntents.parse] must round-trip every [FylzCommand] its actions name, and reject anything
 * it does not recognise -- a null action, a platform action, or [FylzIntents.ACTION_OPEN_FOLDER]
 * missing its required tree extra -- with `null` rather than throwing.
 *
 * `@RunWith(RobolectricTestRunner)`: `Uri.parse` needs a real framework shadow, the same reason
 * [io.github.mbaliga.fylz.history.RecentOpensStoreTest] opts in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FylzIntentsTest {

    private fun uri(tail: String) = Uri.parse("content://fylz.test/tree/$tail")

    // ── the four action-only commands ────────────────────────────────────────────────

    @Test
    fun `ACTION_SCAN parses to Scan`() {
        assertEquals(FylzCommand.Scan, FylzIntents.parse(Intent(FylzIntents.ACTION_SCAN)))
    }

    @Test
    fun `ACTION_SEARCH parses to FocusSearch`() {
        assertEquals(FylzCommand.FocusSearch, FylzIntents.parse(Intent(FylzIntents.ACTION_SEARCH)))
    }

    @Test
    fun `ACTION_OPEN_SHELF parses to OpenShelf`() {
        assertEquals(FylzCommand.OpenShelf, FylzIntents.parse(Intent(FylzIntents.ACTION_OPEN_SHELF)))
    }

    @Test
    fun `ACTION_OPEN_TRASH parses to OpenTrash`() {
        assertEquals(FylzCommand.OpenTrash, FylzIntents.parse(Intent(FylzIntents.ACTION_OPEN_TRASH)))
    }

    // ── OpenFolder ────────────────────────────────────────────────────────────────────

    @Test
    fun `ACTION_OPEN_FOLDER with both extras round-trips through applyOpenFolderExtras`() {
        val intent = FylzIntents.applyOpenFolderExtras(Intent(), uri("root"), uri("root/sub"))

        assertEquals(FylzCommand.OpenFolder(uri("root"), uri("root/sub")), FylzIntents.parse(intent))
    }

    @Test
    fun `ACTION_OPEN_FOLDER built by hand round-trips too`() {
        val intent = Intent(FylzIntents.ACTION_OPEN_FOLDER)
            .putExtra(FylzIntents.EXTRA_TREE_URI, uri("root").toString())
            .putExtra(FylzIntents.EXTRA_FOLDER_URI, uri("root/sub").toString())

        assertEquals(FylzCommand.OpenFolder(uri("root"), uri("root/sub")), FylzIntents.parse(intent))
    }

    @Test
    fun `ACTION_OPEN_FOLDER with only the tree extra leaves folderUri null`() {
        val intent = FylzIntents.applyOpenFolderExtras(Intent(), uri("root"), folderUri = null)

        assertEquals(FylzCommand.OpenFolder(uri("root"), null), FylzIntents.parse(intent))
    }

    @Test
    fun `ACTION_OPEN_FOLDER without a tree extra is rejected`() {
        assertNull(FylzIntents.parse(Intent(FylzIntents.ACTION_OPEN_FOLDER)))
    }

    @Test
    fun `ACTION_OPEN_FOLDER whose tree extra was explicitly stored null is rejected the same way`() {
        // Bundle.putString(key, null) stores the key with a null value, indistinguishable from
        // the key never having been set -- getStringExtra returns null either way, so this must
        // be rejected exactly like the "no extra at all" case above.
        val intent = Intent(FylzIntents.ACTION_OPEN_FOLDER).putExtra(FylzIntents.EXTRA_TREE_URI, null as String?)
        assertNull(FylzIntents.parse(intent))
    }

    // ── garbage ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown custom action is rejected`() {
        assertNull(FylzIntents.parse(Intent("io.github.mbaliga.fylz.action.NOT_REAL")))
    }

    @Test
    fun `a platform action is rejected`() {
        assertNull(FylzIntents.parse(Intent(Intent.ACTION_VIEW)))
    }

    @Test
    fun `a null action is rejected`() {
        assertNull(FylzIntents.parse(Intent()))
    }
}
