package io.github.mbaliga.fylz

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P0.12 (defects 12/13): [MainActivity] must actually read `ACTION_VIEW`'s data URI -- from
 * both `onCreate` and `onNewIntent` (it is `singleTask`) -- rather than silently ignoring "Open
 * with Fylz". [viewUriFrom] is the shared logic both call. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {

    @Test
    fun `an ACTION_VIEW intent's data is extracted`() {
        val uri = Uri.parse("content://com.example.provider/document/42")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        assertEquals(uri, viewUriFrom(intent))
    }

    @Test
    fun `a MAIN launcher intent (no data) is not mistaken for a view request`() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        assertNull(viewUriFrom(intent))
    }

    @Test
    fun `a null intent is handled without crashing`() {
        assertNull(viewUriFrom(null))
    }
}
